import PlutusCore.UPLC
import CardanoLedgerApi.V3
import Blaster
import JulcVerification.ControlledMint

namespace JulcVerification.ControlledMintNegative

open CardanoLedgerApi.V3
open PlutusCore.UPLC.Utils
open JulcVerification.ControlledMint (authority)

set_option warn.sorry false

#import_uplc brokenControlledMintValidator PlutusV3 double_cbor_hex
  "artifacts/controlled-mint-broken.compiledCode.hex"

#prep_uplc appliedBrokenControlledMintValidator brokenControlledMintValidator
  mintingInputs 30000

def firstSignerIsAuthority (ctx : ScriptContext) : Prop :=
  match ctx.scriptContextTxInfo.txInfoSignatories with
  | firstSigner :: _ => firstSigner = authority
  | [] => False

/-- The vulnerable source omits the fixed-authority check. -/
def brokenSuccessImpliesAuthority : Prop :=
  ∀ ctx : ScriptContext,
    PlutusCore.UPLC.Utils.isSuccessful
      (appliedBrokenControlledMintValidator.prop ctx) →
    firstSignerIsAuthority ctx

#blaster (gen-cex: 1) (solve-result: 1) [brokenSuccessImpliesAuthority]

end JulcVerification.ControlledMintNegative
