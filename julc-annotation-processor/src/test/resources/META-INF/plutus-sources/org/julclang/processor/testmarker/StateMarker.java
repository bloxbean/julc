package org.julclang.processor.testmarker;

import org.julclang.stdlib.annotation.OnchainLibrary;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A framework's marker annotation, shipped to JuLC as a library source (ADR-071 tests). */
@OnchainLibrary
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface StateMarker {}
