package com.bloxbean.cardano.julc.benchmark;

import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.core.flat.UplcFlatDecoder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.PlutusLanguage;
import com.bloxbean.cardano.julc.vm.truffle.TruffleVmProvider;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark for the GraalVM Truffle JIT CEK machine.
 * <p>
 * Each {@code @Param("file")} is a {@code .flat} UPLC script filename.
 * Files are loaded from the filesystem {@code data/} directory first,
 * falling back to classpath resources.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 1, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
public class CekTruffleBenchmark {

    @Param({
            "auction_1-1.flat", "auction_1-2.flat", "auction_1-3.flat", "auction_1-4.flat",
            "auction_2-1.flat", "auction_2-2.flat", "auction_2-3.flat", "auction_2-4.flat", "auction_2-5.flat",
            "coop-1.flat", "coop-2.flat", "coop-3.flat", "coop-4.flat", "coop-5.flat", "coop-6.flat", "coop-7.flat",
            "crowdfunding-success-1.flat", "crowdfunding-success-2.flat", "crowdfunding-success-3.flat",
            "currency-1.flat",
            "escrow-redeem_1-1.flat", "escrow-redeem_1-2.flat",
            "escrow-redeem_2-1.flat", "escrow-redeem_2-2.flat", "escrow-redeem_2-3.flat",
            "escrow-refund-1.flat",
            "future-increase-margin-1.flat", "future-increase-margin-2.flat", "future-increase-margin-3.flat",
            "future-increase-margin-4.flat", "future-increase-margin-5.flat",
            "future-pay-out-1.flat", "future-pay-out-2.flat", "future-pay-out-3.flat", "future-pay-out-4.flat",
            "future-settle-early-1.flat", "future-settle-early-2.flat",
            "future-settle-early-3.flat", "future-settle-early-4.flat",
            "game-sm-success_1-1.flat", "game-sm-success_1-2.flat",
            "game-sm-success_1-3.flat", "game-sm-success_1-4.flat",
            "game-sm-success_2-1.flat", "game-sm-success_2-2.flat", "game-sm-success_2-3.flat",
            "game-sm-success_2-4.flat", "game-sm-success_2-5.flat", "game-sm-success_2-6.flat",
            "guardrail-sorted-large.flat", "guardrail-sorted-small.flat",
            "guardrail-unsorted-large.flat", "guardrail-unsorted-small.flat",
            "multisig-sm-01.flat", "multisig-sm-02.flat", "multisig-sm-03.flat", "multisig-sm-04.flat",
            "multisig-sm-05.flat", "multisig-sm-06.flat", "multisig-sm-07.flat", "multisig-sm-08.flat",
            "multisig-sm-09.flat", "multisig-sm-10.flat",
            "ping-pong-1.flat", "ping-pong-2.flat", "ping-pong_2-1.flat",
            "prism-1.flat", "prism-2.flat", "prism-3.flat",
            "pubkey-1.flat",
            "stablecoin_1-1.flat", "stablecoin_1-2.flat", "stablecoin_1-3.flat",
            "stablecoin_1-4.flat", "stablecoin_1-5.flat", "stablecoin_1-6.flat",
            "stablecoin_2-1.flat", "stablecoin_2-2.flat", "stablecoin_2-3.flat", "stablecoin_2-4.flat",
            "token-account-1.flat", "token-account-2.flat",
            "uniswap-1.flat", "uniswap-2.flat", "uniswap-3.flat",
            "uniswap-4.flat", "uniswap-5.flat", "uniswap-6.flat",
            "vesting-1.flat"
    })
    String file;

    private Program program;
    private TruffleVmProvider provider;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        provider = new TruffleVmProvider();
        program = UplcFlatDecoder.decodeProgram(CekJavaBenchmark.loadFlatBytes(file));
    }

    @Benchmark
    public EvalResult bench() {
        return provider.evaluate(program, PlutusLanguage.PLUTUS_V3, null);
    }
}
