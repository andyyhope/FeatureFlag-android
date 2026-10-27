package docs

import docs.declaringflags.duplicateKeyMessage
import docs.exportingandimporting.exportedDocument
import docs.recordflags.duplicateRecordKeyProblem
import docs.recordflags.endpointsPayload
import docs.recordflags.nestedRecord
import docs.recordflags.recordSchema
import docs.recordflags.renamedFieldsPayload
import docs.recordflags.storedRecordText
import docs.remoteoverrides.arrayPathPayload
import docs.remoteoverrides.experimentsPayload
import docs.remoteoverrides.onboardingPayload
import docs.remoteoverrides.rejectionReport
import docs.sharingwithacompanionapp.publishedSchema
import docs.sharingwithacompanionapp.publishedSchemaWithAConfig
import docs.troubleshooting.hostNotFoundMessage
import docs.troubleshooting.notPublishedMessage
import docs.troubleshooting.permissionDeniedMessage
import docs.troubleshooting.usesPermissionMissingMessage
import docs.validatingaconfig.checkoutDefaultsReport
import docs.validatingaconfig.draftAuditReport
import docs.validatingaconfig.stagingDefaultsReport

/**
 * The value behind every `<!-- output: name -->` fence, computed by the code the document describes.
 * DocsSyncTest compares each fence with its entry, and fails on an entry no document shows.
 */
object DocOutputs {
    val all: Map<String, () -> String> = mapOf(
        // DeclaringFlags.md
        "declaring-duplicate-keys" to ::duplicateKeyMessage,
        // RecordFlags.md
        "records-duplicate-key" to ::duplicateRecordKeyProblem,
        "records-stored-text" to ::storedRecordText,
        "records-schema" to ::recordSchema,
        "records-endpoints-payload" to { endpointsPayload },
        "records-renamed-payload" to { renamedFieldsPayload },
        "records-nested-record" to { nestedRecord },
        // RemoteOverrides.md
        "remote-onboarding-payload" to { onboardingPayload },
        "remote-array-payload" to { arrayPathPayload },
        "remote-rejection-report" to ::rejectionReport,
        "remote-experiments-payload" to { experimentsPayload },
        // ValidatingAConfig.md
        "validating-audit-report" to ::draftAuditReport,
        "validating-defaults-report" to ::stagingDefaultsReport,
        "validating-record-defaults-report" to ::checkoutDefaultsReport,
        // SharingWithACompanionApp.md
        "sharing-schema" to ::publishedSchema,
        "sharing-schema-effective" to ::publishedSchemaWithAConfig,
        // ExportingAndImporting.md
        "exporting-json" to ::exportedDocument,
        // Troubleshooting.md
        "troubleshooting-uses-permission-missing" to ::usesPermissionMissingMessage,
        "troubleshooting-host-not-found" to ::hostNotFoundMessage,
        "troubleshooting-permission-denied" to ::permissionDeniedMessage,
        "troubleshooting-not-published" to ::notPublishedMessage,
    )
}
