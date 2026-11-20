package com.harbor.tv.engine.definition

import com.harbor.tv.core.model.DefinitionLifecycleState
import com.harbor.tv.core.model.HarborException

/**
 * Offline definition import pipeline: safe parsing -> schema validation -> semantic validation.
 * Never executes network requests.
 */
class DefinitionImporter(
    private val parser: SafeDataParser = SafeDataParser(),
    private val schemaValidator: SchemaValidator = SchemaValidator(),
    private val semanticValidator: SemanticValidator = SemanticValidator()
) {

    fun importAndValidate(rawText: String): ValidationResult {
        val definition = try {
            parser.parse(rawText)
        } catch (e: HarborException) {
            return ValidationResult(
                isValid = false,
                definition = null,
                issues = listOf(
                    ValidationIssue(
                        stage = "parse",
                        code = e.code,
                        path = "$",
                        message = e.message
                    )
                ),
                lifecycleState = DefinitionLifecycleState.INCOMPATIBLE
            )
        }

        val issues = mutableListOf<ValidationIssue>()
        issues.addAll(schemaValidator.validate(definition))
        issues.addAll(semanticValidator.validate(definition))

        val hasErrors = issues.any { it.severity == ValidationSeverity.ERROR }
        val lifecycle = if (hasErrors) {
            DefinitionLifecycleState.INCOMPATIBLE
        } else {
            DefinitionLifecycleState.VALIDATED
        }

        return ValidationResult(
            isValid = !hasErrors,
            definition = if (hasErrors) null else definition,
            issues = issues,
            lifecycleState = lifecycle
        )
    }
}
