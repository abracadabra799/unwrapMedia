package com.multiviewer.parser.conformance

import java.io.File

/** Shared helpers for the PNG / WebP / GIF conformance tests. */
internal fun ConformanceReport.statusOf(id: String): ConformanceStatus = findings.single { it.rule.id == id }.status
internal fun ConformanceReport.idsWith(vararg s: ConformanceStatus): Set<String> = findings.filter { it.status in s }.map { it.rule.id }.toSet()
internal fun ConformanceReport.notPassing(): List<String> =
    findings.filter { it.status != ConformanceStatus.PASS && it.status != ConformanceStatus.NOT_APPLICABLE }.map { "${it.status} ${it.rule.id}: ${it.details}" }

internal fun conformanceFixture(name: String): File =
    File(ConformanceReport::class.java.getResource("/png-webp-gif-conformance/$name")!!.toURI())
