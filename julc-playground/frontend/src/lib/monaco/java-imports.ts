/** What a validator can import: FrontendSourcesCompileTest compiles them all and checks none is missing. */
export const ONCHAIN_IMPORTS = [
  'java.math.BigInteger',
  'java.util.List',
  'java.util.Map',
  'java.util.Optional',
  'org.julclang.core.PlutusData',
  'org.julclang.core.types.AssetEntry',
  'org.julclang.core.types.JulcArray',
  'org.julclang.core.types.JulcG1',
  'org.julclang.core.types.JulcG1Points',
  'org.julclang.core.types.JulcG2',
  'org.julclang.core.types.JulcG2Points',
  'org.julclang.core.types.JulcList',
  'org.julclang.core.types.JulcMap',
  'org.julclang.core.types.JulcMlResult',
  'org.julclang.core.types.JulcScalars',
  'org.julclang.core.types.JulcValue',
  'org.julclang.core.types.Tuple2',
  'org.julclang.core.types.Tuple3',
  'org.julclang.ledger.Address',
  'org.julclang.ledger.Committee',
  'org.julclang.ledger.Constitution',
  'org.julclang.ledger.Credential',
  'org.julclang.ledger.DRep',
  'org.julclang.ledger.DatumHash',
  'org.julclang.ledger.Delegatee',
  'org.julclang.ledger.GovernanceAction',
  'org.julclang.ledger.GovernanceActionId',
  'org.julclang.ledger.Interval',
  'org.julclang.ledger.IntervalBound',
  'org.julclang.ledger.IntervalBoundType',
  'org.julclang.ledger.OutputDatum',
  'org.julclang.ledger.PolicyId',
  'org.julclang.ledger.ProposalProcedure',
  'org.julclang.ledger.ProtocolVersion',
  'org.julclang.ledger.PubKeyHash',
  'org.julclang.ledger.Rational',
  'org.julclang.ledger.ScriptContext',
  'org.julclang.ledger.ScriptHash',
  'org.julclang.ledger.ScriptInfo',
  'org.julclang.ledger.ScriptPurpose',
  'org.julclang.ledger.StakingCredential',
  'org.julclang.ledger.TokenName',
  'org.julclang.ledger.TxCert',
  'org.julclang.ledger.TxId',
  'org.julclang.ledger.TxInInfo',
  'org.julclang.ledger.TxInfo',
  'org.julclang.ledger.TxOut',
  'org.julclang.ledger.TxOutRef',
  'org.julclang.ledger.ValidatorHash',
  'org.julclang.ledger.Value',
  'org.julclang.ledger.Vote',
  'org.julclang.ledger.Voter',
  'org.julclang.stdlib.Builtins',
  'org.julclang.stdlib.annotation.CertifyingValidator',
  'org.julclang.stdlib.annotation.Entrypoint',
  'org.julclang.stdlib.annotation.MintingValidator',
  'org.julclang.stdlib.annotation.MultiValidator',
  'org.julclang.stdlib.annotation.NewType',
  'org.julclang.stdlib.annotation.OnchainLibrary',
  'org.julclang.stdlib.annotation.Param',
  'org.julclang.stdlib.annotation.ProposingValidator',
  'org.julclang.stdlib.annotation.Purpose',
  'org.julclang.stdlib.annotation.SpendingValidator',
  'org.julclang.stdlib.annotation.VotingValidator',
  'org.julclang.stdlib.annotation.WithdrawValidator',
  'org.julclang.stdlib.lib.AddressLib',
  'org.julclang.stdlib.lib.BitwiseLib',
  'org.julclang.stdlib.lib.BlsLib',
  'org.julclang.stdlib.lib.ByteStringLib',
  'org.julclang.stdlib.lib.ContextsLib',
  'org.julclang.stdlib.lib.CryptoLib',
  'org.julclang.stdlib.lib.IntervalLib',
  'org.julclang.stdlib.lib.ListsLib',
  'org.julclang.stdlib.lib.MapLib',
  'org.julclang.stdlib.lib.MathLib',
  'org.julclang.stdlib.lib.NativeValueLib',
  'org.julclang.stdlib.lib.OutputLib',
  'org.julclang.stdlib.lib.TypedArrayLib',
  'org.julclang.stdlib.lib.TypedMapLib',
  'org.julclang.stdlib.lib.ValuesLib',
];

export interface ImportSegment {
  name: string;
  isPackage: boolean;
}

/** The next segments after `prefix`, which is empty or ends in a dot: packages first, then classes. */
export function nextImportSegments(prefix: string): ImportSegment[] {
  const segments = new Map<string, boolean>();
  for (const name of ONCHAIN_IMPORTS) {
    if (!name.startsWith(prefix)) continue;
    const [next, ...rest] = name.substring(prefix.length).split('.');
    segments.set(next, rest.length > 0);
  }
  return [...segments].map(([name, isPackage]) => ({ name, isPackage }))
    .sort((a, b) => Number(b.isPackage) - Number(a.isPackage) || a.name.localeCompare(b.name));
}
