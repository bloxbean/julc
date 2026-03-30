package com.bloxbean.julc.cli.cmd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplCommandTest {

    @Test
    void hasUnmatchedBrackets_balanced_returnsFalse() {
        assertFalse(ReplCommand.hasUnmatchedBrackets("1 + 2"));
        assertFalse(ReplCommand.hasUnmatchedBrackets("foo(1, 2)"));
        assertFalse(ReplCommand.hasUnmatchedBrackets("{ return 1; }"));
        assertFalse(ReplCommand.hasUnmatchedBrackets(""));
    }

    @Test
    void hasUnmatchedBrackets_openParen_returnsTrue() {
        assertTrue(ReplCommand.hasUnmatchedBrackets("foo(1,"));
        assertTrue(ReplCommand.hasUnmatchedBrackets("List.of(1, 2, 3"));
    }

    @Test
    void hasUnmatchedBrackets_openCurly_returnsTrue() {
        assertTrue(ReplCommand.hasUnmatchedBrackets("if (true) {"));
        assertTrue(ReplCommand.hasUnmatchedBrackets("new byte[]{1, 2"));
    }

    @Test
    void hasUnmatchedBrackets_insideString_ignored() {
        assertFalse(ReplCommand.hasUnmatchedBrackets("\"hello { world\""));
    }
}
