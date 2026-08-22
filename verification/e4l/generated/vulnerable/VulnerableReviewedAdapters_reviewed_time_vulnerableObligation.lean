/- Generated exact-artifact compositional DSL obligation. -/
import PlutusCore.UPLC
import CardanoLedgerApi.V3
import Blaster
import GeneratedSchemas
import CheckedExecution
import SecurityProperty

namespace JulcGenerated.VulnerableReviewedAdapters_reviewed_time_vulnerable

open CardanoLedgerApi.V3
open PlutusCore.UPLC.Utils
open JulcGenerated.UserProperty

#import_uplc validator PlutusV3 double_cbor_hex
  "artifacts/vulnerable-reviewed-adapters.compiledCode.hex"
#prep_uplc appliedValidator validator spendingInputs 5000

def composedObligation : Prop :=
  ∀ ctx : ScriptContext,
    selectedPurpose ctx = true →
    blasterValidSpendingContext ctx = true →
    PlutusCore.UPLC.Utils.isSuccessful (appliedValidator.prop ctx) →
    dslProperty_reviewed_time_vulnerable ctx

def hasNoSuccessfulInput : Prop :=
  ∀ ctx : ScriptContext,
    selectedPurpose ctx = true →
    blasterValidSpendingContext ctx = true →
    ¬PlutusCore.UPLC.Utils.isSuccessful (appliedValidator.prop ctx)

end JulcGenerated.VulnerableReviewedAdapters_reviewed_time_vulnerable
