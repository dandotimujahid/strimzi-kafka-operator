/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.test.executor;

import io.strimzi.test.k8s.exceptions.KubeClusterException;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.join;

/**
 * Class provide execution of external command
 */
public class Exec {
    private static final Logger LOGGER = LogManager.getLogger(Exec.class);
    private static final Pattern ERROR_PATTERN = Pattern.compile("Error from server \\(([a-zA-Z0-9]+)\\):");
    private static final Pattern INVALID_PATTERN = Pattern.compile("The ([a-zA-Z0-9]+) \"([a-z0-9.-]+)\" is invalid:");
    private static final Pattern PATH_SPLITTER = Pattern.compile(System.getProperty("path.separator"));
    private static final int MAXIMUM_EXEC_LOG_CHARACTER_SIZE = Integer.parseInt(System.getenv().getOrDefault("STRIMZI_EXEC_MAX_LOG_OUTPUT_CHARACTERS", "20000"));
    private static final Object LOCK = new Object();

    public Process process;
    private String stdOut;
    private String stdErr;
    private StreamGobbler stdOutReader;
    private StreamGobbler stdErrReader;
    private Path logPath;
    private boolean appendLineSeparator;

    public Exec() {
        this.appendLineSeparator = true;
    }

    public Exec(Path logPath) {
        this.appendLineSeparator = true;
        this.logPath = logPath;
    }

    public Exec(boolean appendLineSeparator) {
        this.appendLineSeparator = appendLineSeparator;
    }

    /**
     * Getter for stdOutput
     *
     * @return string stdOut
     */
    public String out() {
        return stdOut;
    }

    /**
     * Getter for stdErrorOutput
     *
     * @return string stdErr
     */
    public String err() {
        return stdErr;
    }

    public boolean isRunning() {
        return process.isAlive();
    }

    public int getRetCode() {
        LOGGER.info("Process: {}", process);
        if (isRunning()) {
            return -1;
        } else {
            return process.exitValue();
        }
    }


    /**
     * Method executes external command
     *
     * @param command arguments for command
     * @return execution results
     */
    public static ExecResult exec(String... command) {
        return exec(Arrays.asList(command));
    }

    /**
     * Method executes external command
     *
     * @param command arguments for command
     * @return execution results
     */
    public static ExecResult exec(Level level, String... command) {
        List<String> commands = new ArrayList<>(Arrays.asList(command));
        return exec(null, commands, 0, level);
    }

    /**
     * Method executes external command
     *
     * @param command arguments for command
     * @return execution results
     */
    public static ExecResult exec(List<String> command) {
        return exec(null, command, 0, Level.DEBUG);
    }

    /**
     * Method executes external command
     *
     * @param command arguments for command
     * @return execution results
     */
    public static ExecResult exec(String input, List<String> command) {
        return exec(input, command, 0, Level.DEBUG);
    }

    /**
     * Method executes external command
     * @param command arguments for command
     * @param timeout timeout for execution
     * @param logLevel log output level
     * @return execution results
     */
    public static ExecResult exec(String input, List<String> command, int timeout, Level logLevel) {
        return exec(input, command, timeout, logLevel, true);
    }

    /**
     * Method executes external command
     * @param command arguments for command
     * @param timeout timeout for execution
     * @param logLevel log output level
     * @param throwErrors look for errors in output and throws exception if true
     * @return execution results
     */
    public static ExecResult exec(String input, List<String> command, int timeout, Level logLevel, boolean throwErrors) {
        int ret = 1;
        ExecResult execResult;
        try {
            Exec executor = new Exec();
            ret = executor.execute(input, command, timeout);
            synchronized (LOCK) {
                String log = ret != 0 ? "Failed to exec command" : "Command";
                logData(logLevel, String.format("%s: '%s' (return code: %s)", log, String.join(" ", command), ret));
                if (input != null && !input.contains("CustomResourceDefinition")) {
                    logData(logLevel, String.format("Input: %s", input.trim()));
                }
                if (ret != 0) {
                    if (!executor.out().isEmpty()) {
                        logData(logLevel, "======STDOUT START=======");
                        logData(logLevel, String.format("%s", cutExecutorLog(executor.out().trim())));
                        logData(logLevel, "======STDOUT END======");
                    }
                    if (!executor.err().isEmpty()) {
                        logData(logLevel, "======STDERR START=======");
                        logData(logLevel, String.format("%s", cutExecutorLog(executor.err().trim())));
                        logData(logLevel, "======STDERR END======");
                    } else {
                        if (!executor.out().isEmpty()) {
                            logData(Level.TRACE, "======STDOUT START=======");
                            logData(Level.TRACE, String.format("%s", cutExecutorLog(executor.out().trim())));
                            logData(Level.TRACE, "======STDOUT END======");
                        }
                        if (!executor.err().isEmpty()) {
                            logData(Level.TRACE, "======STDERR START=======");
                            logData(Level.TRACE, String.format("%s", cutExecutorLog(executor.err().trim())));
                            logData(Level.TRACE, "======STDERR END======");
                        }
                    }
                }
            }

            execResult = new ExecResult(ret, executor.out(), executor.err());

            if (throwErrors && ret != 0) {
                String msg = "`" + join(" ", command) + "` got status code " + ret + " and stderr:\n------\n" + executor.stdErr + "\n------\nand stdout:\n------\n" + executor.stdOut + "\n------";

                Matcher matcher = ERROR_PATTERN.matcher(executor.err());
                KubeClusterException t = null;

                if (matcher.find()) {
                    switch (matcher.group(1)) {
                        case "NotFound":
                            t = new KubeClusterException.NotFound(execResult, msg);
                            break;
                        case "AlreadyExists":
                            t = new KubeClusterException.AlreadyExists(execResult, msg);
                            break;
                        default:
                            break;
                    }
                }
                matcher = INVALID_PATTERN.matcher(executor.err());
                if (matcher.find()) {
                    t = new KubeClusterException.InvalidResource(execResult, msg);
                }
                if (t == null) {
                    t = new KubeClusterException(execResult, msg);
                }
                throw t;
            }
            return new ExecResult(ret, executor.out(), executor.err());

        } catch (IOException | ExecutionException e) {
            throw new KubeClusterException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KubeClusterException(e);
        }
    }

    /**
     * Method executes external command
     *
     * @param commands arguments for command
     * @param timeoutMs  timeout in ms for kill
     * @return returns ecode of execution
     * @throws IOException When writing/closing the OutputStreams or starting the process
     * @throws InterruptedException In the waitFor method, if the wait is interrupted
     * @throws ExecutionException When getting the output from std
     */
    public int execute(String input, List<String> commands, long timeoutMs) throws IOException, InterruptedException, ExecutionException {
        LOGGER.trace("Running command - " + join(" ", commands.toArray(new String[0])));
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(commands);
        builder.directory(new File(System.getProperty("user.dir")));
        process = builder.start();
        OutputStream outputStream = process.getOutputStream();
        if (input != null) {
            LOGGER.trace("With stdin {}", input);
            outputStream.write(input.getBytes(Charset.defaultCharset()));
        }
        // Close subprocess' stdin
        outputStream.close();

        Future<String> output = readStdOutput();
        Future<String> error = readStdError();

        int retCode = 1;
        if (timeoutMs > 0) {
            if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                retCode = process.exitValue();
            } else {
                process.destroyForcibly();
            }
        } else {
            retCode = process.waitFor();
        }

        try {
            stdOut = output.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            output.cancel(true);
            stdOut = stdOutReader.getData();
        }

        try {
            stdErr = error.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            error.cancel(true);
            stdErr = stdErrReader.getData();
        }
        storeOutputsToFile();

        return retCode;
    }

    /**
     * Method kills process
     */
    public void stop() {
        process.destroyForcibly();
        stdOut = stdOutReader.getData();
        stdErr = stdErrReader.getData();
    }

    /**
     * Get standard output of execution
     *
     * @return future string output
     */
    private Future<String> readStdOutput() {
        stdOutReader = new StreamGobbler(process.getInputStream());
        return stdOutReader.read();
    }

    /**
     * Get standard error output of execution
     *
     * @return future string error output
     */
    private Future<String> readStdError() {
        stdErrReader = new StreamGobbler(process.getErrorStream());
        return stdErrReader.read();
    }

    /**
     * Get stdOut and stdErr and store it into files
     */
    private void storeOutputsToFile() {
        if (logPath != null) {
            try {
                Files.createDirectories(logPath);
                Files.write(Paths.get(logPath.toString(), "stdOutput.log"), stdOut.getBytes(Charset.defaultCharset()));
                Files.write(Paths.get(logPath.toString(), "stdError.log"), stdErr.getBytes(Charset.defaultCharset()));
            } catch (Exception ex) {
                LOGGER.warn("Cannot save output of execution: " + ex.getMessage());
            }
        }
    }

    /**
     * Check if command is executable
     * @param cmd command
     * @return true.false
     */
    public static boolean isExecutableOnPath(String cmd) {
        var osName = System.getProperty("os.name");
        if (osName.toLowerCase(Locale.US).startsWith("windows")) {
            cmd += ".exe";
        }

        for (String dir : PATH_SPLITTER.split(System.getenv("PATH"))) {
            if (new File(dir, cmd).canExecute()) {
                return true;
            }
        }
        return false;
    }

    /**
     * This method check the size of executor output log and cut it if it's too long.
     * @param log executor log
     * @return updated log if size is too big
     */
    public static String cutExecutorLog(String log) {
        if (log.length() > MAXIMUM_EXEC_LOG_CHARACTER_SIZE) {
            LOGGER.warn("Executor log is too long. Going to strip it and print only first {} characters", MAXIMUM_EXEC_LOG_CHARACTER_SIZE);
            return log.substring(0, MAXIMUM_EXEC_LOG_CHARACTER_SIZE);
        }
        return log;
    }

    /**
     * Class represent async reader
     */
    class StreamGobbler {
        private InputStream is;
        private StringBuilder data = new StringBuilder();

        /**
         * Constructor of StreamGobbler
         *
         * @param is input stream for reading
         */
        StreamGobbler(InputStream is) {
            this.is = is;
        }

        /**
         * Return data from stream sync
         *
         * @return string of data
         */
        public String getData() {
            return data.toString();
        }

        /**
         * read method
         *
         * @return return future string of output
         */
        public Future<String> read() {
            return CompletableFuture.supplyAsync(() -> {
                Scanner scanner = new Scanner(is, StandardCharsets.UTF_8.name());
                try {
                    while (scanner.hasNextLine()) {
                        data.append(scanner.nextLine());
                        if (appendLineSeparator) {
                            data.append(System.getProperty("line.separator"));
                        }
                    }
                    scanner.close();
                    return data.toString();
                } catch (Exception e) {
                    throw new CompletionException(e);
                } finally {
                    scanner.close();
                }
            }, runnable -> new Thread(runnable).start());
        }
    }

    private static void logData(Level level, String log) {
        if (level.equals(Level.INFO)) {
            LOGGER.info(log);
        } else if (level.equals(Level.DEBUG)) {
            LOGGER.debug(log);
        } else if (level.equals(Level.TRACE)) {
            LOGGER.trace(log);
        } else if (level.equals(Level.WARN)) {
            LOGGER.warn(log);
        } else if (level.equals(Level.ERROR)) {
            LOGGER.error(log);
        }
    }
}
