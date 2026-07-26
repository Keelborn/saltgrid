package com.jokerdayn.swworldgencore.diagnostics;

import java.nio.file.Path;

/** Absolute paths written by {@link GeneratorDiagnostics#export}. */
public record ExportResult(Path reportPath, Path csvPath) {}
