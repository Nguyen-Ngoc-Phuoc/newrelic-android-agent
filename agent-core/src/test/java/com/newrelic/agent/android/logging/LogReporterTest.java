/*
 * Copyright (c) 2024. New Relic Corporation. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.newrelic.agent.android.logging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.newrelic.agent.android.AgentConfiguration;
import com.newrelic.agent.android.FeatureFlag;
import com.newrelic.agent.android.util.Streams;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class LogReporterTest extends LoggingTests {

    private AgentConfiguration agentConfiguration;
    private LogReporter logReporter;
    private RemoteLogger logger;

    @BeforeClass
    public static void beforeClass() throws Exception {
        LoggingTests.beforeClass();
    }

    @Before
    public void setUp() throws Exception {
        FeatureFlag.enableFeature(FeatureFlag.LogReporting);
        LogReporting.setLogLevel(LogLevel.WARN);

        agentConfiguration = new AgentConfiguration();
        agentConfiguration.setApplicationToken("APP-TOKEN>");
        agentConfiguration.setLogReportingConfiguration(new LogReportingConfiguration(true, LogLevel.DEBUG));

        logReporter = Mockito.spy(LogReporter.initialize(reportsDir, agentConfiguration));
        LogReporter.MIN_PAYLOAD_THRESHOLD = 0;  // disable min archive size checking
        LogReporter.instance.set(logReporter);

        logger = Mockito.spy((RemoteLogger) LogReporting.getLogger());
    }

    @After
    public void tearDown() throws Exception {
        FeatureFlag.disableFeature(FeatureFlag.LogReporting);
        Streams.list(LogReporter.logDataStore).forEach(file -> file.delete());
    }

    @Test
    public void initialize() throws IOException {
        LogReporter.instance.set(null);

        try {
            LogReporter.initialize(new File("/"), AgentConfiguration.getInstance());
            Assert.fail("Should fail with missing or un-writable report directory");
        } catch (Exception e) {
            Assert.assertNull(LogReporter.instance.get());
        }

        try {
            LogReporter.initialize(File.createTempFile("logReporting", "tmp"), AgentConfiguration.getInstance());
            Assert.fail("Should fail with a non-directory file");
        } catch (Exception e) {
            Assert.assertNull(LogReporter.instance.get());
        }

        try {
            LogReporter.initialize(reportsDir, null);
            Assert.fail("Should fail with missing agent configuration");
        } catch (Exception e) {
            Assert.assertNull(LogReporter.instance.get());
        }

        LogReporter.initialize(reportsDir, agentConfiguration);
        Assert.assertNotNull(LogReporter.instance.get());
        Assert.assertTrue(reportsDir.exists() && reportsDir.canWrite());
    }

    @Test
    public void start() {
        logReporter.start();
        verify(logReporter, times(1)).onHarvestStart();
    }

    @Test
    public void stop() {
        logReporter.stop();
        verify(logReporter, times(1)).onHarvestStop();
    }

    @Test
    public void startWhenDisabled() {
        logReporter.setEnabled(false);
        logReporter.start();
        verify(logReporter, never()).onHarvestStart();
    }

    @Test
    public void stopWhenDisabled() {
        logReporter.setEnabled(false);
        logReporter.stop();
        verify(logReporter, never()).onHarvestStop();
    }

    @Test
    public void onHarvestStart() {
        logReporter.onHarvestStart();
        verify(logReporter, never()).onHarvest();
        verify(logReporter, atMostOnce()).expire(anyLong());
        verify(logReporter, atMostOnce()).cleanup();
    }

    @Test
    public void onHarvestStop() {
        logReporter.onHarvestStop();
        verify(logReporter, atMostOnce()).onHarvest();
    }

    @Test
    public void onHarvest() throws Exception {
        seedLogData(2);

        logReporter.onHarvest();
        verify(logReporter, times(1)).rollupLogDataFiles();
        verify(logReporter, times(1)).postLogReport(any(File.class));
    }

    @Test
    public void onHarvestConfigurationChanged() {
        agentConfiguration.getLogReportingConfiguration().setLoggingEnabled(false);
        logReporter.onHarvestConfigurationChanged();
        verify(logReporter, times(1)).setEnabled(false);

        agentConfiguration.getLogReportingConfiguration().setLoggingEnabled(true);
        logReporter.onHarvestConfigurationChanged();
        verify(logReporter, times(1)).setEnabled(true);
    }

    @Test
    public void getCachedLogReports() throws Exception {
        Set<File> completedLogFiles = logReporter.getCachedLogReports(LogReporter.LogReportState.CLOSED);
        Assert.assertEquals(4, completedLogFiles.size(), 7);
    }

    @Test
    public void mergeLogDataToArchive() throws Exception {
        seedLogData(7);

        File archivedLogFile = logReporter.rollupLogDataFiles();
        Assert.assertTrue((archivedLogFile.exists() && archivedLogFile.isFile() && archivedLogFile.length() > 0));
        Assert.assertFalse(archivedLogFile.canWrite());
        JsonArray jsonArray = new Gson().fromJson(Streams.newBufferedFileReader(archivedLogFile), JsonArray.class);
        Assert.assertEquals(7 * LogReporting.getLogLevelAsInt(), jsonArray.size());   // 7 logs, 2 (WARN) entries per log
    }

    @Test
    public void mergeLogDataToArchiveWithOverflow() throws Exception {
        // Shush up logging (it takes more time to run)
        AgentLogManager.getAgentLog().setLevel(0);

        // add few more to overflow the rollup
        seedLogData(7, LogReporter.VORTEX_PAYLOAD_LIMIT / 5);

        File archivedLogFile = logReporter.rollupLogDataFiles();

        // should be at least one rollup file
        Assert.assertEquals(archivedLogFile.length(), LogReporter.VORTEX_PAYLOAD_LIMIT, LogReporter.VORTEX_PAYLOAD_LIMIT / 10);

        // and a few leftover close files
        Set<File> leftOvers = logReporter.getCachedLogReports(LogReporter.LogReportState.CLOSED);
        Assert.assertFalse(leftOvers.isEmpty());
    }

    @Test
    public void mergeLogDataToEmptyArchive() throws Exception {
        File archivedLogFile = logReporter.rollupLogDataFiles();
        Assert.assertNull(archivedLogFile);
    }

    @Test
    public void mergeLogDataToMinThresholdArchive() throws Exception {
        LogReporter.MIN_PAYLOAD_THRESHOLD = LogReporter.VORTEX_PAYLOAD_LIMIT / 10;
        seedLogData(1, LogReporter.MIN_PAYLOAD_THRESHOLD / 2);

        File archivedLogFile = logReporter.rollupLogDataFiles();
        Assert.assertNull("Don't archive if total log data size is below threshold", archivedLogFile);

        seedLogData(2, LogReporter.MIN_PAYLOAD_THRESHOLD / 2);
        archivedLogFile = logReporter.rollupLogDataFiles();
        Assert.assertNotNull(archivedLogFile);
        Assert.assertTrue((archivedLogFile.exists() && archivedLogFile.isFile() && archivedLogFile.length() > LogReporter.MIN_PAYLOAD_THRESHOLD));
    }

    @Test
    public void postLogReport() throws Exception {
        AgentLogManager.getAgentLog().setLevel(AgentLog.WARN);
        seedLogData(333);

        File archivedLogFile = logReporter.rollupLogDataFiles();
        AgentLogManager.getAgentLog().setLevel(AgentLog.DEBUG);
        logReporter.postLogReport(archivedLogFile);
    }

    @Test
    public void getWorkingLogfile() throws IOException {
        File workingLogFile = logReporter.getWorkingLogfile();
        Assert.assertTrue(workingLogFile.exists());
        Assert.assertEquals(workingLogFile, new File(LogReporter.logDataStore,
                String.format(Locale.getDefault(),
                        LogReporter.LOG_FILE_MASK,
                        "",
                        LogReporter.LogReportState.WORKING.extension)));
    }

    @Test
    public void rollLogfile() throws IOException {
        long tStart = System.currentTimeMillis();
        File workingLogFile = logReporter.getWorkingLogfile();
        Assert.assertEquals(tStart, workingLogFile.lastModified(), 100);
        Assert.assertTrue(workingLogFile.exists());

        File closeLogFile = logReporter.rollLogfile(workingLogFile);
        Assert.assertTrue(closeLogFile.exists());
        Assert.assertFalse(workingLogFile.exists());
        Assert.assertTrue(closeLogFile.lastModified() >= tStart);
    }

    @Test
    public void rollWorkingLogFile() throws IOException {
        Assert.assertNotNull(logReporter.workingLogFile);
        Assert.assertTrue(logReporter.workingLogFile.exists());

        Assert.assertNotNull(logReporter.workingLogFileWriter.get());
        BufferedWriter writer = logReporter.workingLogFileWriter.get();

        RemoteLogger remoteLogger = new RemoteLogger();
        remoteLogger.log(LogLevel.INFO, getRandomMsg(10));
        logger.flush();

        File finalizedLogFile = logReporter.rollWorkingLogFile();
        Assert.assertTrue(finalizedLogFile.exists());
        Assert.assertTrue(logReporter.workingLogFile.exists());
        Assert.assertNotEquals("Should create a new buffered writer", writer, logReporter.workingLogFileWriter.get());
    }

    @Test
    public void safeDelete() throws Exception {
        File closedLogFile = logReporter.rollLogfile(logReporter.getWorkingLogfile());
        Assert.assertTrue(closedLogFile.exists());
        logReporter.safeDelete(closedLogFile);
        Assert.assertFalse(closedLogFile.exists());
        Assert.assertFalse(closedLogFile.canWrite());
        Assert.assertTrue(logReporter.isLogfileTypeOf(closedLogFile, LogReporter.LogReportState.CLOSED));
    }

    @Test
    public void safeDeleteExpired() throws Exception {
        seedLogData(3);
        logReporter.getCachedLogReports(LogReporter.LogReportState.CLOSED).forEach(file -> logReporter.safeDelete(file));
        logReporter.getCachedLogReports(LogReporter.LogReportState.EXPIRED).forEach(file -> {
            logReporter.safeDelete(file);
            Assert.assertTrue(file.exists());
        });
    }

    @Test
    public void recover() throws Exception {
        seedLogData(3);

        logReporter.getCachedLogReports(LogReporter.LogReportState.CLOSED).forEach(file -> logReporter.safeDelete(file));
        Assert.assertTrue(logReporter.getCachedLogReports(LogReporter.LogReportState.CLOSED).isEmpty());

        logReporter.getCachedLogReports(LogReporter.LogReportState.EXPIRED).forEach(file -> Assert.assertFalse(file.canWrite()));
        Assert.assertEquals(3, logReporter.getCachedLogReports(LogReporter.LogReportState.EXPIRED).size());

        logReporter.recover();
        logReporter.getCachedLogReports(LogReporter.LogReportState.CLOSED).forEach(file -> Assert.assertTrue(file.canWrite()));
    }

    @Test
    public void expire() {
        Set<File> allFiles = logReporter.getCachedLogReports(LogReporter.LogReportState.CLOSED);
        allFiles.forEach(file -> Assert.assertTrue(file.setLastModified(System.currentTimeMillis() - TimeUnit.MILLISECONDS.convert(4, TimeUnit.DAYS))));
        logReporter.expire(logReporter.reportTTL);
        allFiles.forEach(file -> {
            Assert.assertFalse(file.exists());
            Assert.assertTrue(new File(file.getAbsolutePath() + "." + LogReporter.LogReportState.EXPIRED.extension).exists());
        });
    }

    @Test
    public void cleanup() {
        Set<File> closedFiles = logReporter.getCachedLogReports(LogReporter.LogReportState.CLOSED);
        closedFiles.forEach(file -> logReporter.safeDelete(file));
        Set<File> expiredFiles = logReporter.getCachedLogReports(LogReporter.LogReportState.EXPIRED);
        logReporter.cleanup();
        expiredFiles.forEach(file -> {
            Assert.assertFalse(file.exists());
        });
    }

    @Test
    public void logFileNameAsParts() throws Exception {
        seedLogData(1);

        Set<File> files = logReporter.getCachedLogReports(LogReporter.LogReportState.CLOSED);
        File logDataFile = files.iterator().next();
        Map<String, String> parts = logReporter.logFileNameAsParts(logDataFile);

        Assert.assertTrue(parts.containsKey("path") && new File(parts.get("path")).exists());
        Assert.assertTrue(parts.containsKey("file") && logDataFile.getName().equals(String.format(Locale.getDefault(), "%s.%s", parts.get("file"), parts.get("extension"))));
    }

    @Test
    public void typeOfLogfile() throws Exception {
        seedLogData(1);

        Set<File> files = logReporter.getCachedLogReports(LogReporter.LogReportState.CLOSED);

        LogReporter.LogReportState state = logReporter.typeOfLogfile(files.iterator().next());
        Assert.assertEquals(LogReporter.LogReportState.CLOSED, state);
        Assert.assertNotEquals(LogReporter.LogReportState.ALL, state);
        try {
            logReporter.typeOfLogfile(new File("/wut/the/fudge?"));
            Assert.fail("Should throw IOException");
        } catch (IOException e) {
        }
    }

    @Test
    public void isLogfileTypeOf() throws Exception {
        seedLogData(1);

        Set<File> files = logReporter.getCachedLogReports(LogReporter.LogReportState.CLOSED);
        File logDataFile = files.iterator().next();

        Assert.assertTrue(logReporter.isLogfileTypeOf(logDataFile, LogReporter.LogReportState.CLOSED));
        Assert.assertFalse(logReporter.isLogfileTypeOf(logDataFile, LogReporter.LogReportState.ALL));
        Assert.assertFalse(logReporter.isLogfileTypeOf(logDataFile, LogReporter.LogReportState.WORKING));

        File archive = logReporter.rollupLogDataFiles();
        Assert.assertTrue(logReporter.isLogfileTypeOf(archive, LogReporter.LogReportState.ROLLUP));
    }

    @Test
    public void testPayloadBudget() throws Exception {
        logReporter.payloadBudget = 1000;
        logger.log(LogLevel.INFO, getRandomMsg(300));
        logger.log(LogLevel.INFO, getRandomMsg(300));
        logger.log(LogLevel.INFO, getRandomMsg(400));
        logger.flush();

        verify(logReporter, atMostOnce()).rollWorkingLogFile();
    }

    @Test
    public void testHarvestLifecycle() throws Exception {
        seedLogData(3);

        Assert.assertNotNull(logReporter.workingLogFile);
        Assert.assertTrue(logReporter.workingLogFile.exists());

        logger.log(LogReporting.getLogLevel(), "Before onHarvestStart()");
        logReporter.onHarvestStart();
        Assert.assertNotNull(logReporter.workingLogFileWriter.get());

        BufferedWriter writer = logReporter.workingLogFileWriter.get();
        logger.log(LogLevel.INFO, "After onHarvestStart()");

        logger.log(LogReporting.getLogLevel(), "Before onHarvest()");
        logReporter.onHarvest();
        Assert.assertTrue(logReporter.workingLogFile.exists());
        Assert.assertEquals("Should create a new working file", 0, logReporter.workingLogFile.length());
        Assert.assertNotEquals("Should create a new buffered writer", writer, logReporter.workingLogFileWriter.get());
        logger.log(LogReporting.getLogLevel(), "After onHarvest()");
        logger.log(LogReporting.getLogLevel(), "Before onHarvestStop()");

        logReporter.onHarvestStop();
        logger.flush();
        Assert.assertTrue(logReporter.workingLogFile.exists());
        Assert.assertEquals("Should create a new working file", 0, logReporter.workingLogFile.length());
        Assert.assertNotEquals("Should create a new buffered writer", writer, logReporter.workingLogFileWriter.get());
        Assert.assertFalse("Should not shutdown executor", logger.executor.isShutdown());
        logger.log(LogLevel.INFO, "After onHarvestStop()");
    }

    @Test
    public void testWorkingfileIOFailure() throws IOException {
        final String msg = "";

        logger.log(LogLevel.ERROR, msg + getRandomMsg(8));
        logger.log(LogLevel.WARN, msg + getRandomMsg(5));
        logger.log(LogLevel.INFO, msg + getRandomMsg(11));
        logger.log(LogLevel.DEBUG, msg + getRandomMsg(11));
        logger.log(LogLevel.VERBOSE, msg + getRandomMsg(11));

        logger.flush();

        // close working file writer without finalizing
        logReporter.workingLogFileWriter.get().flush();
        logReporter.workingLogFileWriter.get().close();

        verifyWorkingLogFile(2);
        logger = new RemoteLogger();

        JsonArray jsonArray = verifyWorkingLogFile(2);
        Assert.assertNotNull(jsonArray);
    }

    @Test
    public void finalizeLogData() {
        final String msg = "Log msg";

        Assert.assertFalse(logger.executor.isShutdown());
        logger.log(LogLevel.ERROR, msg);
        logger.log(LogLevel.WARN, msg);
        logger.log(LogLevel.INFO, msg);
        logger.log(LogLevel.VERBOSE, msg);
        logger.log(LogLevel.DEBUG, msg);
        logReporter.finalizeWorkingLogFile();

        Assert.assertNull(logReporter.workingLogFileWriter.get());
    }

    @Test
    public void shutdown() {
        Assert.assertFalse(logger.executor.isShutdown());
        logger.log(LogLevel.INFO, getRandomMsg(128));
        logger.log(LogLevel.INFO, getRandomMsg(64));
        logger.shutdown();

        Assert.assertTrue(logger.executor.isShutdown());
    }


}

