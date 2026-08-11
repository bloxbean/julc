package com.bloxbean.julc.cli.cmd.verify;

/** A fail-closed verification preflight result, mapped to CLI exit code 2. */
public class UnsupportedVerificationException extends Exception {
    public UnsupportedVerificationException(String message) {
        super(message);
    }
}
