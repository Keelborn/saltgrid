package com.jokerdayn.swworldgencore.command;

import com.jokerdayn.swworldgencore.diagnostics.ExportResult;
import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.worldgen.OceanChunkGenerator;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /oceangen benchmark ...} — reads the generator's instrumentation.
 *
 * <p>Every long report is echoed to {@code latest.log} as well as to the command source,
 * because it does not fit legibly in chat.</p>
 */
final class BenchmarkCommand {

    private static final Logger LOG = LoggerFactory.getLogger("SWWorldgenCore");

    private BenchmarkCommand() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oceangen")
            .requires(source -> source.hasPermission(CommandSupport.PERMISSION_LEVEL))
            .then(Commands.literal("benchmark")
                .executes(BenchmarkCommand::status)
                .then(Commands.literal("status").executes(BenchmarkCommand::status))
                .then(Commands.literal("report").executes(BenchmarkCommand::report))
                .then(view("phases", false, GeneratorDiagnostics::phaseStatus))
                .then(view("terrain", false, GeneratorDiagnostics::counterStatus))
                .then(view("caches", true, GeneratorDiagnostics::cacheStatus))
                .then(view("threads", false, GeneratorDiagnostics::threadStatus))
                .then(view("runtime", false, GeneratorDiagnostics::runtimeStatus))
                .then(view("active", false, GeneratorDiagnostics::activeStatus))
                .then(view("slow", false, GeneratorDiagnostics::slowStatus))
                .then(view("diagnose", true, GeneratorDiagnostics::diagnosis))
                .then(Commands.literal("histograms").executes(BenchmarkCommand::histograms))
                .then(Commands.literal("reset").executes(BenchmarkCommand::reset))
                .then(Commands.literal("export").executes(BenchmarkCommand::export))
                .then(Commands.literal("enabled")
                    .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(context -> setEnabled(
                            context, BoolArgumentType.getBool(context, "value")
                        ))))
                .then(Commands.literal("verbose")
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(context -> setVerbose(
                            context, BoolArgumentType.getBool(context, "enabled")
                        ))))
            ));
    }

    /**
     * A text view of the instrumentation.
     *
     * @param refreshCacheSizes whether the view's numbers require cache occupancy to be
     *                          re-published first
     */
    private static LiteralArgumentBuilder<CommandSourceStack> view(
        String name,
        boolean refreshCacheSizes,
        Function<GeneratorDiagnostics, String> renderer
    ) {
        return Commands.literal(name).executes(context -> {
            OceanChunkGenerator generator = resolve(context);
            if (generator == null) return 0;
            if (refreshCacheSizes) generator.publishCacheSizes();
            return send(context, renderer.apply(generator.diagnostics()));
        });
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        OceanChunkGenerator generator = resolve(context);
        if (generator == null) return 0;
        generator.publishCacheSizes();
        context.getSource().sendSuccess(
            () -> Component.literal(generator.diagnostics().compactStatus(generator.getSeed())),
            false
        );
        return 1;
    }

    private static int report(CommandContext<CommandSourceStack> context) {
        OceanChunkGenerator generator = resolve(context);
        if (generator == null) return 0;
        generator.publishCacheSizes();
        generator.diagnostics().forceReport(generator.getSeed());
        context.getSource().sendSuccess(
            () -> Component.literal(
                generator.diagnostics().compactStatus(generator.getSeed())
                    + " | full report written to latest.log"
            ),
            false
        );
        return 1;
    }

    private static int histograms(CommandContext<CommandSourceStack> context) {
        OceanChunkGenerator generator = resolve(context);
        if (generator == null) return 0;
        LOG.info("\n{}", generator.diagnostics().histogramStatus());
        context.getSource().sendSuccess(
            () -> Component.literal("OceanGen histograms written to latest.log"),
            false
        );
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        OceanChunkGenerator generator = resolve(context);
        if (generator == null) return 0;
        generator.diagnostics().reset();
        generator.publishCacheSizes();
        context.getSource().sendSuccess(
            () -> Component.literal("OceanGen benchmark reset; seed=" + generator.getSeed()),
            true
        );
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> context) {
        OceanChunkGenerator generator = resolve(context);
        if (generator == null) return 0;
        generator.publishCacheSizes();
        try {
            ExportResult result = generator.diagnostics().export(
                FMLPaths.GAMEDIR.get().resolve("logs").resolve("oceangen"),
                generator.getSeed()
            );
            context.getSource().sendSuccess(
                () -> Component.literal(
                    "OceanGen benchmark exported:\n"
                        + result.reportPath() + "\n" + result.csvPath()
                ),
                false
            );
            return 1;
        } catch (IOException error) {
            LOG.error("Failed to export OceanGen benchmark", error);
            context.getSource().sendFailure(Component.literal(
                "Could not export the benchmark: " + error.getMessage()
            ));
            return 0;
        }
    }

    private static int setEnabled(CommandContext<CommandSourceStack> context, boolean enabled) {
        OceanChunkGenerator generator = resolve(context);
        if (generator == null) return 0;
        GeneratorDiagnostics diagnostics = generator.diagnostics();
        boolean changed = diagnostics.enabled() != enabled;
        diagnostics.setEnabled(enabled);
        // Turning it back on starts a clean window: otherwise the pre-pause numbers would be
        // averaged over a gap of unmeasured work.
        if (enabled && changed) {
            diagnostics.reset();
            generator.publishCacheSizes();
        }
        context.getSource().sendSuccess(
            () -> Component.literal(
                "OceanGen benchmark enabled=" + enabled
                    + (enabled && changed ? "; started a new window" : "")
            ),
            true
        );
        return 1;
    }

    private static int setVerbose(CommandContext<CommandSourceStack> context, boolean verbose) {
        OceanChunkGenerator generator = resolve(context);
        if (generator == null) return 0;
        generator.diagnostics().setVerbose(verbose);
        context.getSource().sendSuccess(
            () -> Component.literal("OceanGen verbose=" + verbose),
            false
        );
        return 1;
    }

    private static int send(CommandContext<CommandSourceStack> context, String rendered) {
        LOG.info("\n{}", rendered);
        context.getSource().sendSuccess(() -> Component.literal(rendered), false);
        return 1;
    }

    private static OceanChunkGenerator resolve(CommandContext<CommandSourceStack> context) {
        return CommandSupport.generatorFor(context.getSource(), context.getSource().getLevel());
    }
}
