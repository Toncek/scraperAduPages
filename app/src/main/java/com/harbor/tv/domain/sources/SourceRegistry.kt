package com.harbor.tv.domain.sources

import com.harbor.tv.core.model.DefinitionLifecycleState
import com.harbor.tv.core.model.HarborErrorCode
import com.harbor.tv.core.model.HarborException
import com.harbor.tv.core.storage.CatalogCacheDao
import com.harbor.tv.core.storage.SourceRecordDao
import com.harbor.tv.core.storage.SourceRecordEntity
import com.harbor.tv.engine.definition.DefinitionImporter
import com.harbor.tv.engine.definition.SourceDefinition
import com.harbor.tv.engine.definition.ValidationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Manages imported declarative [SourceDefinition] records, permission approval comparison,
 * atomic update activation preserving previous working revisions, version-based cache
 * invalidation, and 1-click rollback (Master Specification Sections 6.3, 21.2, 21.3, 24).
 */
class SourceRegistry(
    private val sourceRecordDao: SourceRecordDao,
    private val catalogCacheDao: CatalogCacheDao? = null,
    private val importer: DefinitionImporter = DefinitionImporter(),
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {

    data class ApprovalDiff(
        val addedCatalogHosts: Set<String> = emptySet(),
        val addedImageHosts: Set<String> = emptySet(),
        val addedMediaHosts: Set<String> = emptySet(),
        val addedAuthenticationHosts: Set<String> = emptySet(),
        val authChanged: Boolean = false,
        val httpsRelaxed: Boolean = false,
        val lanAccessAdded: Boolean = false
    ) {
        val hasExpandedHosts: Boolean
            get() = addedCatalogHosts.isNotEmpty() ||
                addedImageHosts.isNotEmpty() ||
                addedMediaHosts.isNotEmpty() ||
                addedAuthenticationHosts.isNotEmpty()

        val requiresReapproval: Boolean
            get() = hasExpandedHosts || authChanged || httpsRelaxed || lanAccessAdded
    }

    data class RegisteredSource(
        val record: SourceRecordEntity,
        val definition: SourceDefinition?,
        val lifecycleState: DefinitionLifecycleState
    )

    data class ImportOutcome(
        val validationResult: ValidationResult,
        val record: SourceRecordEntity?,
        val approvalDiff: ApprovalDiff? = null,
        val requiresUserApproval: Boolean = true
    )

    private val mutex = Mutex()
    private val pendingUpdates = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun observeSources(): Flow<List<RegisteredSource>> {
        return sourceRecordDao.observeAll().map { entities ->
            entities.map { entity -> toRegisteredSource(entity) }
        }
    }

    suspend fun getAllSources(): List<RegisteredSource> {
        return sourceRecordDao.getAll().map { toRegisteredSource(it) }
    }

    suspend fun getSource(sourceId: String): RegisteredSource? {
        val entity = sourceRecordDao.getById(sourceId) ?: return null
        return toRegisteredSource(entity)
    }

    suspend fun getEnabledDefinitions(): List<SourceDefinition> {
        return sourceRecordDao.getAll().mapNotNull { entity ->
            if (entity.lifecycleState == DefinitionLifecycleState.ENABLED.name) {
                parseDefinitionOrNull(entity.rawContent)
            } else {
                null
            }
        }
    }

    suspend fun getEnabledSourceIds(): Set<String> {
        return sourceRecordDao.getAll()
            .filter { it.lifecycleState == DefinitionLifecycleState.ENABLED.name }
            .map { it.sourceId }
            .toSet()
    }

    suspend fun getEnabledDefinition(sourceId: String): SourceDefinition? {
        val entity = sourceRecordDao.getById(sourceId) ?: return null
        if (entity.lifecycleState != DefinitionLifecycleState.ENABLED.name) return null
        return parseDefinitionOrNull(entity.rawContent)
    }

    /**
     * Imports and validates [rawText] completely offline (no network requests).
     * New sources enter [DefinitionLifecycleState.AWAITING_APPROVAL]; updates to an existing
     * source enter [DefinitionLifecycleState.PENDING_UPDATE] until approved/activated.
     */
    suspend fun importDefinition(rawText: String): ImportOutcome = mutex.withLock {
        val validation = importer.importAndValidate(rawText)
        val definition = validation.definition
        if (!validation.isValid || definition == null) {
            return@withLock ImportOutcome(
                validationResult = validation,
                record = null,
                approvalDiff = null,
                requiresUserApproval = true
            )
        }

        val sourceId = definition.source.id
        val existing = sourceRecordDao.getById(sourceId)
        val existingDef = existing?.let { parseDefinitionOrNull(it.rawContent) }
        val diff = existingDef?.let { compareApproval(it, definition) }
        val now = clockMs()

        val record = if (existing == null) {
            SourceRecordEntity(
                sourceId = sourceId,
                version = definition.source.version,
                rawContent = rawText,
                lifecycleState = DefinitionLifecycleState.AWAITING_APPROVAL.name,
                approvedHostsJson = "",
                previousRawContent = null,
                previousVersion = null,
                updatedAt = now
            )
        } else {
            // Preserve current working definition & prior rollback revision until explicit update activation
            pendingUpdates[sourceId] = rawText
            SourceRecordEntity(
                sourceId = sourceId,
                version = existing.version,
                rawContent = existing.rawContent,
                lifecycleState = DefinitionLifecycleState.PENDING_UPDATE.name,
                approvedHostsJson = existing.approvedHostsJson,
                previousRawContent = existing.previousRawContent,
                previousVersion = existing.previousVersion,
                updatedAt = now
            )
        }

        sourceRecordDao.upsert(record)
        ImportOutcome(
            validationResult = validation,
            record = record,
            approvalDiff = diff,
            requiresUserApproval = existing == null || (diff?.requiresReapproval ?: true)
        )
    }

    /**
     * Approves and activates an imported definition for the first time, enforcing that
     * `reference-only` profiles cannot bypass explicit validation and user approval.
     */
    suspend fun approveAndEnable(
        sourceId: String,
        userApproved: Boolean
    ): RegisteredSource = mutex.withLock {
        if (!userApproved) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Explicit user approval is required before enabling source '$sourceId'",
                sourceId = sourceId
            )
        }
        val existing = sourceRecordDao.getById(sourceId)
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Source '$sourceId' is not registered",
                sourceId = sourceId
            )

        val validation = importer.importAndValidate(existing.rawContent)
        val definition = validation.definition
        if (!validation.isValid || definition == null) {
            val incompatible = existing.copy(
                lifecycleState = DefinitionLifecycleState.INCOMPATIBLE.name,
                updatedAt = clockMs()
            )
            sourceRecordDao.upsert(incompatible)
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Source '$sourceId' failed validation and cannot be enabled",
                sourceId = sourceId
            )
        }

        enforceReferenceOnlyPolicy(definition, userApproved = true)

        val updated = existing.copy(
            version = definition.source.version,
            lifecycleState = DefinitionLifecycleState.ENABLED.name,
            approvedHostsJson = encodeApprovedPermissions(definition),
            updatedAt = clockMs()
        )
        sourceRecordDao.upsert(updated)
        catalogCacheDao?.deleteForSourceExceptVersion(sourceId, definition.source.version)
        toRegisteredSource(updated, definition)
    }

    /**
     * Atomically applies an update to an existing source definition:
     * 1. Validates [newRawContent] before activation.
     * 2. Compares permissions against the currently approved definition.
     * 3. Requires [userApproved] whenever network hosts expand, HTTPS/LAN relax, or auth changes.
     * 4. Preserves the previous working revision (`previousRawContent`, `previousVersion`) for rollback.
     * 5. Invalidates cached catalog entries from previous definition versions.
     */
    suspend fun applyUpdate(
        sourceId: String,
        newRawContent: String,
        userApproved: Boolean = false
    ): RegisteredSource = mutex.withLock {
        val existing = sourceRecordDao.getById(sourceId)
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Cannot update unknown source '$sourceId'",
                sourceId = sourceId
            )

        val validation = importer.importAndValidate(newRawContent)
        val newDef = validation.definition
        if (!validation.isValid || newDef == null) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Updated definition for '$sourceId' failed validation: " +
                    validation.errors.joinToString("; ") { it.message },
                sourceId = sourceId
            )
        }
        if (newDef.source.id != sourceId) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Updated definition source.id '${newDef.source.id}' does not match '$sourceId'",
                sourceId = sourceId
            )
        }

        val currentDef = parseDefinitionOrNull(existing.rawContent)
        val diff = currentDef?.let { compareApproval(it, newDef) }
        if ((diff == null || diff.requiresReapproval) && !userApproved) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Updated definition for '$sourceId' expands network hosts or alters authentication and requires renewed user approval",
                sourceId = sourceId
            )
        }

        enforceReferenceOnlyPolicy(newDef, userApproved = userApproved || !(diff?.requiresReapproval ?: true))

        val now = clockMs()
        val updated = SourceRecordEntity(
            sourceId = sourceId,
            version = newDef.source.version,
            rawContent = newRawContent,
            lifecycleState = DefinitionLifecycleState.ENABLED.name,
            approvedHostsJson = encodeApprovedPermissions(newDef),
            previousRawContent = existing.rawContent,
            previousVersion = existing.version,
            updatedAt = now
        )
        sourceRecordDao.upsert(updated)
        pendingUpdates.remove(sourceId)
        catalogCacheDao?.deleteForSourceExceptVersion(sourceId, newDef.source.version)
        toRegisteredSource(updated, newDef)
    }

    /**
     * Approves and activates a staged update previously imported via [importDefinition].
     */
    suspend fun approvePendingUpdate(
        sourceId: String,
        userApproved: Boolean
    ): RegisteredSource {
        val stagedRaw = pendingUpdates[sourceId]
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "No pending update staged for '$sourceId'",
                sourceId = sourceId
            )
        return applyUpdate(sourceId, stagedRaw, userApproved = userApproved)
    }

    /**
     * Performs a 1-click atomic rollback to the preserved previous working revision.
     */
    suspend fun rollback(sourceId: String): RegisteredSource = mutex.withLock {
        val existing = sourceRecordDao.getById(sourceId)
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Source '$sourceId' is not registered",
                sourceId = sourceId
            )
        val previousRaw = existing.previousRawContent
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "No previous working revision is available for rollback on '$sourceId'",
                sourceId = sourceId
            )

        val validation = importer.importAndValidate(previousRaw)
        val previousDef = validation.definition
        if (!validation.isValid || previousDef == null) {
            throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Previous revision for '$sourceId' is no longer valid",
                sourceId = sourceId
            )
        }

        val rolledBack = SourceRecordEntity(
            sourceId = sourceId,
            version = previousDef.source.version,
            rawContent = previousRaw,
            lifecycleState = DefinitionLifecycleState.ENABLED.name,
            approvedHostsJson = encodeApprovedPermissions(previousDef),
            previousRawContent = existing.rawContent,
            previousVersion = existing.version,
            updatedAt = clockMs()
        )
        sourceRecordDao.upsert(rolledBack)
        catalogCacheDao?.deleteForSourceExceptVersion(sourceId, previousDef.source.version)
        toRegisteredSource(rolledBack, previousDef)
    }

    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean): RegisteredSource = mutex.withLock {
        val existing = sourceRecordDao.getById(sourceId)
            ?: throw HarborException(
                HarborErrorCode.DEFINITION_INVALID,
                "Source '$sourceId' is not registered",
                sourceId = sourceId
            )

        if (enabled && existing.approvedHostsJson.isBlank()) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Source '$sourceId' has not been approved by the user yet",
                sourceId = sourceId
            )
        }

        val targetState = if (enabled) {
            DefinitionLifecycleState.ENABLED
        } else {
            DefinitionLifecycleState.DISABLED
        }
        val updated = existing.copy(
            lifecycleState = targetState.name,
            updatedAt = clockMs()
        )
        sourceRecordDao.upsert(updated)
        toRegisteredSource(updated)
    }

    suspend fun markBroken(sourceId: String) = mutex.withLock {
        if (sourceRecordDao.getById(sourceId) != null) {
            sourceRecordDao.updateLifecycleState(
                sourceId = sourceId,
                lifecycleState = DefinitionLifecycleState.BROKEN.name,
                updatedAt = clockMs()
            )
        }
    }

    suspend fun removeSource(sourceId: String) = mutex.withLock {
        catalogCacheDao?.deleteForSource(sourceId)
        sourceRecordDao.deleteById(sourceId)
    }

    fun compareApproval(
        current: SourceDefinition,
        candidate: SourceDefinition
    ): ApprovalDiff {
        val currentCatalog = normalizeHostSet(current.network.allowedHosts.catalog)
        val currentImages = normalizeHostSet(current.network.allowedHosts.images)
        val currentMedia = normalizeHostSet(current.network.allowedHosts.media)
        val currentAuthHosts = normalizeHostSet(current.network.allowedHosts.authentication)

        val newCatalog = normalizeHostSet(candidate.network.allowedHosts.catalog)
        val newImages = normalizeHostSet(candidate.network.allowedHosts.images)
        val newMedia = normalizeHostSet(candidate.network.allowedHosts.media)
        val newAuthHosts = normalizeHostSet(candidate.network.allowedHosts.authentication)

        val authChanged = current.authentication.type != candidate.authentication.type ||
            current.authentication.profileId != candidate.authentication.profileId ||
            current.authentication.scopes.toSet() != candidate.authentication.scopes.toSet()

        val httpsRelaxed = current.network.httpsOnly && !candidate.network.httpsOnly
        val lanAccessAdded = !current.network.allowLan && candidate.network.allowLan

        return ApprovalDiff(
            addedCatalogHosts = newCatalog - currentCatalog,
            addedImageHosts = newImages - currentImages,
            addedMediaHosts = newMedia - currentMedia,
            addedAuthenticationHosts = newAuthHosts - currentAuthHosts,
            authChanged = authChanged,
            httpsRelaxed = httpsRelaxed,
            lanAccessAdded = lanAccessAdded
        )
    }

    fun encodeApprovedPermissions(definition: SourceDefinition): String {
        val hosts = definition.network.allowedHosts
        return buildJsonObject {
            put("catalog", buildJsonArray { hosts.catalog.forEach { add(JsonPrimitive(it)) } })
            put("images", buildJsonArray { hosts.images.forEach { add(JsonPrimitive(it)) } })
            put("media", buildJsonArray { hosts.media.forEach { add(JsonPrimitive(it)) } })
            put("authentication", buildJsonArray { hosts.authentication.forEach { add(JsonPrimitive(it)) } })
            put("httpsOnly", definition.network.httpsOnly)
            put("allowLan", definition.network.allowLan)
            put("authType", definition.authentication.type)
            if (definition.authentication.profileId != null) {
                put("authProfileId", definition.authentication.profileId)
            }
            put("authScopes", buildJsonArray { definition.authentication.scopes.forEach { add(JsonPrimitive(it)) } })
        }.toString()
    }

    fun decodeApprovedPermissionsSummary(approvedHostsJson: String): Map<String, List<String>> {
        if (approvedHostsJson.isBlank()) return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(approvedHostsJson).jsonObject
            mapOf(
                "catalog" to readStringArray(obj, "catalog"),
                "images" to readStringArray(obj, "images"),
                "media" to readStringArray(obj, "media"),
                "authentication" to readStringArray(obj, "authentication")
            )
        }.getOrDefault(emptyMap())
    }

    private fun enforceReferenceOnlyPolicy(definition: SourceDefinition, userApproved: Boolean) {
        val isReferenceOnly = definition.settingsReference?.sourceStatus == MagicPhSettingsAdapter.REFERENCE_ONLY_STATUS
        if (isReferenceOnly && !userApproved) {
            throw HarborException(
                HarborErrorCode.POLICY_BLOCKED,
                "Definition '${definition.source.id}' carries reference-only status and cannot activate without user approval",
                sourceId = definition.source.id
            )
        }
        val profile = MagicPhSettingsAdapter.findById(definition.source.id)
        if (profile != null) {
            MagicPhSettingsAdapter.assertActivationAllowed(profile, definition, userApproved)
        }
    }

    private fun toRegisteredSource(
        entity: SourceRecordEntity,
        parsedDefinition: SourceDefinition? = null
    ): RegisteredSource {
        val state = runCatching { DefinitionLifecycleState.valueOf(entity.lifecycleState) }
            .getOrDefault(DefinitionLifecycleState.IMPORTED)
        val definition = parsedDefinition ?: parseDefinitionOrNull(entity.rawContent)
        return RegisteredSource(
            record = entity,
            definition = definition,
            lifecycleState = state
        )
    }

    private fun parseDefinitionOrNull(rawContent: String): SourceDefinition? {
        return importer.importAndValidate(rawContent).definition
    }

    private fun normalizeHostSet(hosts: List<String>): Set<String> {
        return hosts.map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun readStringArray(obj: JsonObject, key: String): List<String> {
        val arr = obj[key] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull }
    }
}
