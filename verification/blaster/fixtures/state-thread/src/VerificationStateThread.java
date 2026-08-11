import java.math.BigInteger;

@SpendingValidator
class VerificationStateThread {
    record StateDatum(byte[] owner, BigInteger state) {}
    record Transition(BigInteger nextState) {}

    @Entrypoint
    static boolean validate(StateDatum datum, Transition redeemer, PlutusData ctx) {
        var contextFields = Builtins.sndPair(Builtins.unConstrData(ctx));
        var txInfo = Builtins.headList(contextFields);
        var scriptInfo = Builtins.headList(
                Builtins.tailList(Builtins.tailList(contextFields)));
        var txFields = Builtins.sndPair(Builtins.unConstrData(txInfo));
        var inputs = Builtins.unListData(Builtins.headList(txFields));
        var outputs = Builtins.unListData(Builtins.headList(
                Builtins.tailList(Builtins.tailList(txFields))));
        var signatories = Builtins.unListData(Builtins.headList(
                Builtins.tailList(Builtins.tailList(Builtins.tailList(
                Builtins.tailList(Builtins.tailList(Builtins.tailList(
                Builtins.tailList(Builtins.tailList(txFields))))))))));

        if (Builtins.nullList(signatories)
                || Builtins.nullList(inputs)
                || Builtins.nullList(outputs)) {
            return false;
        }
        if (Builtins.equalsByteString(
                Builtins.unBData(Builtins.headList(signatories)), datum.owner())) {
            if (redeemer.nextState().compareTo(datum.state()) > 0) {
                var scriptInfoPair = Builtins.unConstrData(scriptInfo);
                var currentRef = Builtins.headList(Builtins.sndPair(scriptInfoPair));
                var ownInputFields = Builtins.sndPair(Builtins.unConstrData(
                        Builtins.headList(inputs)));
                var ownInputRef = Builtins.headList(ownInputFields);
                if (Builtins.equalsData(ownInputRef, currentRef)) {
                    var resolvedInput = Builtins.headList(
                            Builtins.tailList(ownInputFields));
                    var resolvedFields = Builtins.sndPair(
                            Builtins.unConstrData(resolvedInput));
                    var inputValue = Builtins.headList(
                            Builtins.tailList(resolvedFields));
                    var nextOutput = Builtins.headList(outputs);
                    var outputFields = Builtins.sndPair(
                            Builtins.unConstrData(nextOutput));
                    var outputValue = Builtins.headList(
                            Builtins.tailList(outputFields));
                    if (Builtins.equalsData(inputValue, outputValue)) {
                        var outputDatum = Builtins.headList(
                                Builtins.tailList(Builtins.tailList(outputFields)));
                        var expectedFields = Builtins.mkCons(
                                currentRef,
                                Builtins.mkCons(
                                        Builtins.iData(redeemer.nextState()),
                                        Builtins.mkNilData()));
                        var expectedDatum = Builtins.constrData(0, expectedFields);
                        return Builtins.equalsData(
                                outputDatum,
                                Builtins.constrData(
                                        2,
                                        Builtins.mkCons(
                                                expectedDatum,
                                                Builtins.mkNilData())));
                    }
                }
            }
        }
        return false;
    }
}
