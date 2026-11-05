/**
 * JuLC's Java frontend: javac reads and attributes the sources, and this package lowers its trees to PIR (ADR-068).
 * <p>
 * Comments here say "the reference" or "the JavaParser frontend" for the frontend ADR-068 M6 removed, which set the bytes,
 * words and positions this one was held to. Its answers stay as data: {@code adr/evidence/068/lowering-reference.tsv} and
 * {@code lowering-i1.tsv}, and the frozen table of the lowering tests.
 */
package org.julclang.compiler.frontend.javac;
