package com.baafoo.agent.bootstrap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baafoo.agent.BaafooAgent;
import com.baafoo.agent.GlobalRouteState;

/**
 * Reflectively synchronizes the App-CL {@code GlobalRouteState} state into the
 * Bootstrap-CL copy of the same class. The Bootstrap-CL copy is a separate
 * class instance (loaded from the helper JAR appended by
 * {@link BootstrapClassPathSetup}); its static fields are independent from the
 * App-CL version, so every field must be set via reflection.
 *
 * <p>This is a static utility class extracted from {@code BaafooAgent}.</p>
 */
public class BootstrapStateSync {

    private static final Logger log = LoggerFactory.getLogger(BootstrapStateSync.class);

    @SuppressWarnings("unchecked")
    public static void syncGlobalRouteStateToBootstrapCL() {
        try {
            Class<?> bootGRS = BootstrapClassPathSetup.findBootstrapClass("com.baafoo.agent.GlobalRouteState");

            Object bootRoutesObj = bootGRS.getField("ROUTES").get(null);
            if (bootRoutesObj instanceof ConcurrentHashMap) {
                Class<?> bootHostPortClass = Class.forName("com.baafoo.agent.GlobalRouteState$HostPort", false, bootGRS.getClassLoader());
                java.lang.reflect.Constructor<?> ctor = bootHostPortClass.getConstructor(String.class, int.class);
                // Build a new map and atomically swap the ROUTES field reference,
                // avoiding the clear+putAll window where concurrent readers see an empty table.
                // NOTE: bootHostPort instances are from the Bootstrap CL HostPort class, which is
                // a *different* class from the App CL GlobalRouteState.HostPort. We use a raw
                // ConcurrentHashMap to avoid a ClassCastException across class-loader boundaries.
                @SuppressWarnings({"unchecked", "rawtypes"})
                ConcurrentHashMap newBootRoutes = new ConcurrentHashMap();
                for (Map.Entry<String, GlobalRouteState.HostPort> entry : GlobalRouteState.ROUTES.entrySet()) {
                    Object bootHostPort = ctor.newInstance(entry.getValue().host, entry.getValue().port);
                    newBootRoutes.put(entry.getKey(), bootHostPort);
                }
                bootGRS.getField("ROUTES").set(null, newBootRoutes);
                BaafooAgent.updateBootstrapRoutes(newBootRoutes);
                BootstrapClassPathSetup.setBootstrapHostPortCtor(ctor);
                log.info("Synced {} routes to Bootstrap CL GlobalRouteState.ROUTES (atomic swap)", newBootRoutes.size());
            }

            bootGRS.getField("CURRENT_MODE").setInt(null, GlobalRouteState.CURRENT_MODE);

            BootstrapClassPathSetup.setBootstrapRef(bootGRS, "SERVER_HOST", GlobalRouteState.SERVER_HOST);
            BootstrapClassPathSetup.setBootstrapRef(bootGRS, "SERVER_HOST_IP", GlobalRouteState.SERVER_HOST_IP);
            BootstrapClassPathSetup.setBootstrapInt(bootGRS, "SERVER_PORT", GlobalRouteState.SERVER_PORT);

            BootstrapClassPathSetup.setBootstrapInt(bootGRS, "HTTP_PORT", GlobalRouteState.HTTP_PORT);
            BootstrapClassPathSetup.setBootstrapInt(bootGRS, "TCP_PORT", GlobalRouteState.TCP_PORT);
            BootstrapClassPathSetup.setBootstrapInt(bootGRS, "KAFKA_PORT", GlobalRouteState.KAFKA_PORT);
            BootstrapClassPathSetup.setBootstrapInt(bootGRS, "PULSAR_PORT", GlobalRouteState.PULSAR_PORT);
            BootstrapClassPathSetup.setBootstrapInt(bootGRS, "JMS_PORT", GlobalRouteState.JMS_PORT);
            BootstrapClassPathSetup.setBootstrapInt(bootGRS, "GRPC_PORT", GlobalRouteState.GRPC_PORT);

            BootstrapClassPathSetup.setBootstrapGRSClass(bootGRS);

            log.info("Synced GlobalRouteState fields to Bootstrap CL: CURRENT_MODE={}, SERVER_HOST={}, SERVER_HOST_IP={}, SERVER_PORT={}, " +
                            "HTTP_PORT={}, TCP_PORT={}, KAFKA_PORT={}, PULSAR_PORT={}, JMS_PORT={}, GRPC_PORT={}",
                    GlobalRouteState.CURRENT_MODE, GlobalRouteState.SERVER_HOST, GlobalRouteState.SERVER_HOST_IP, GlobalRouteState.SERVER_PORT,
                    GlobalRouteState.HTTP_PORT, GlobalRouteState.TCP_PORT, GlobalRouteState.KAFKA_PORT,
                    GlobalRouteState.PULSAR_PORT, GlobalRouteState.JMS_PORT,
                    GlobalRouteState.GRPC_PORT);
        } catch (Exception e) {
            log.error("Failed to sync GlobalRouteState to Bootstrap CL: {}", e.getMessage(), e);
        }
    }

    /**
     * Sync the recording stream wrapper functions to the Bootstrap CL copy of GlobalRouteState.
     */
    public static void syncRecordingWrappersToBootstrapCL() {
        try {
            Class<?> bootGRS = BootstrapClassPathSetup.getBootstrapGRSClass();
            if (bootGRS == null) {
                log.warn("Bootstrap CL GlobalRouteState class not found, skipping recording wrapper sync");
                return;
            }

            java.lang.reflect.Field iswField = BootstrapClassPathSetup.requireBootstrapField(bootGRS, "INPUT_STREAM_WRAPPER");
            java.lang.reflect.Field oswField = BootstrapClassPathSetup.requireBootstrapField(bootGRS, "OUTPUT_STREAM_WRAPPER");
            java.lang.reflect.Field nioField = BootstrapClassPathSetup.requireBootstrapField(bootGRS, "NIO_RECORDING_HANDLER");
            java.lang.reflect.Field pluginConsultExtField = BootstrapClassPathSetup.requireBootstrapField(bootGRS, "PLUGIN_CONSULT_FN_EXT");
            java.lang.reflect.Field eventFireField = BootstrapClassPathSetup.requireBootstrapField(bootGRS, "EVENT_FIRE_FN");

            iswField.set(null, GlobalRouteState.INPUT_STREAM_WRAPPER);
            oswField.set(null, GlobalRouteState.OUTPUT_STREAM_WRAPPER);
            nioField.set(null, GlobalRouteState.NIO_RECORDING_HANDLER);
            pluginConsultExtField.set(null, GlobalRouteState.PLUGIN_CONSULT_FN_EXT);
            eventFireField.set(null, GlobalRouteState.EVENT_FIRE_FN);

            log.info("Synced recording stream wrappers, NIO handler, plugin consult bridge, and event bridge to Bootstrap CL GlobalRouteState");
        } catch (Exception e) {
            log.warn("Failed to sync recording wrappers to Bootstrap CL GlobalRouteState: {}. " +
                    "Stream recording will not work.", e.getMessage());
        }
    }

    /**
     * Sync the SLF4J-backed log handlers to the Bootstrap CL copy of GlobalRouteState.
     * The Bootstrap CL copy is a separate class instance; its static fields are
     * independent from the App CL version, so we must set them via reflection.
     */
    public static void syncLogHandlersToBootstrapCL(Logger adviceLogger) {
        try {
            Class<?> bootGRS = BootstrapClassPathSetup.getBootstrapGRSClass();
            if (bootGRS == null) {
                log.warn("Bootstrap CL GlobalRouteState class not found, skipping log handler sync");
                return;
            }

            Class<?> consumerClass = java.util.function.Consumer.class;
            java.lang.reflect.Field infoField = BootstrapClassPathSetup.requireBootstrapField(bootGRS, "LOG_INFO_HANDLER");
            java.lang.reflect.Field warnField = BootstrapClassPathSetup.requireBootstrapField(bootGRS, "LOG_WARN_HANDLER");
            java.lang.reflect.Field errorField = BootstrapClassPathSetup.requireBootstrapField(bootGRS, "LOG_ERROR_HANDLER");
            java.lang.reflect.Field debugField = BootstrapClassPathSetup.requireBootstrapField(bootGRS, "LOG_DEBUG_HANDLER");

            Consumer<String> infoHandler = (Consumer<String>) adviceLogger::info;
            Consumer<String> warnHandler = (Consumer<String>) adviceLogger::warn;
            Consumer<String> errorHandler = (Consumer<String>) adviceLogger::error;
            Consumer<String> debugHandler = (Consumer<String>) adviceLogger::debug;

            infoField.set(null, infoHandler);
            warnField.set(null, warnHandler);
            errorField.set(null, errorHandler);
            debugField.set(null, debugHandler);

            log.info("Synced SLF4J log handlers (INFO/WARN/ERROR/DEBUG) to Bootstrap CL GlobalRouteState");
        } catch (Exception e) {
            log.warn("Failed to sync log handlers to Bootstrap CL GlobalRouteState: {}. " +
                    "Bootstrap CL advice will fall back to System.out.", e.getMessage());
        }
    }
}
