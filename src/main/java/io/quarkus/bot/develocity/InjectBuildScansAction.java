package io.quarkus.bot.develocity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.kohsuke.github.GHCheckRun;
import org.kohsuke.github.GHCheckRunBuilder.Output;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHWorkflowRun;
import org.kohsuke.github.GHWorkflowRun.Conclusion;
import org.kohsuke.github.GitHub;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.githubaction.Action;
import io.quarkiverse.githubaction.Commands;
import io.quarkiverse.githubaction.Context;
import io.quarkiverse.githubaction.Inputs;

public class InjectBuildScansAction {

    public static final String WORKFLOW_RUN_ID_MARKER = "<!-- Build-Reporter/workflow-run-id:%1$s -->";
    public static final String BUILD_SCANS_CHECK_RUN_MARKER = "<!-- Build-Reporter/build-scans-check-run -->";
    private static final String BUILD_SCANS = "Build scans";

    @Deprecated(forRemoval = true)
    private static final String OLD_WORKFLOW_RUN_ID_MARKER = "<!-- Quarkus-GitHub-Bot/workflow-run-id:%1$s -->";
    @Deprecated(forRemoval = true)
    private static final String OLD_BUILD_SCANS_CHECK_RUN_MARKER = "<!-- Quarkus-GitHub-Bot/build-scans-check-run -->";

    @Inject
    ObjectMapper objectMapper;

    @Action("inject-build-scans")
    void injectBuildScans(Context context, Commands commands, Inputs inputs, GitHub github) {
        commands.notice("Injecting build scans into build report");

        Path buildMetadataJson = null;

        Optional<String> buildMetadataFilePath = inputs.get("build-metadata-file-path");
        if (buildMetadataFilePath.isPresent()) {
            buildMetadataJson = Path.of(buildMetadataFilePath.get());
            if (!Files.isReadable(buildMetadataJson)) {
                commands.warning(buildMetadataJson + " is not readable, ignoring");
                return;
            }
        }

        if (buildMetadataJson == null) {
            commands.warning("Build metadata file path input is not provided, ignoring");
            return;
        }

        OptionalLong workflowRunIdInput = inputs.getLong("workflow-run-id");
        if (workflowRunIdInput.isEmpty()) {
            commands.warning("No workflow run id provided ignoring");
            return;
        }
        long workflowRunId = workflowRunIdInput.getAsLong();

        BuildScanStatuses statuses;
        try {
            statuses = objectMapper.readValue(buildMetadataJson.toFile(), BuildScanStatuses.class);
        } catch (IOException e) {
            commands.error("Unable to parse " + buildMetadataJson + ": " + e.getMessage());
            return;
        }

        Map<String, String> buildScanMapping = statuses.builds.stream()
                .filter(s -> s.jobName != null)
                .collect(Collectors.toMap(s -> s.jobName, s -> s.buildScanLink));

        try {
            GHRepository repository = github.getRepository(context.getGitHubRepository());
            GHPullRequest pullRequest = repository.getPullRequest(statuses.prNumber);
            GHWorkflowRun workflowRun = repository.getWorkflowRun(workflowRunId);

            if (workflowRun.getConclusion() == Conclusion.CANCELLED) {
                return;
            }

            Optional<GHCheckRun> buildScansCheckRun = createBuildScansOutput(commands, workflowRun, statuses);

            if (buildScansCheckRun.isPresent()) {
                commands.appendJobSummary("Develocity build scans have been published for pull request [#"
                        + pullRequest.getNumber() + "](" + pullRequest.getHtmlUrl() + ")\n\n"
                        + "Check run: " + buildScansCheckRun.get().getHtmlUrl());
            }

            updateComment(commands, pullRequest, workflowRun, buildScanMapping, buildScansCheckRun);

            // note for future self: it is not possible to update an existing check run created by another GitHub App
        } catch (IOException e) {
            commands.error("Error trying to attach build scans to pull request #" + statuses.prNumber + ": " + e.getMessage());
        }
    }

    private void updateComment(Commands commands, GHPullRequest pullRequest, GHWorkflowRun workflowRun,
            Map<String, String> buildScanMapping, Optional<GHCheckRun> buildScansCheckRun) {
        try {
            Optional<GHIssueComment> reportCommentCandidate = getPullRequestComment(commands, workflowRun, pullRequest);

            if (reportCommentCandidate.isEmpty()) {
                commands.warning("Unable to find a report comment to update");
                return;
            }

            GHIssueComment reportComment = reportCommentCandidate.get();

            String updatedCommentBody = reportComment.getBody().lines().map(line -> {
                for (Entry<String, String> buildScanEntry : buildScanMapping.entrySet()) {
                    if (line.contains("| " + buildScanEntry.getKey() + " |")) {
                        return line.replace(":construction:", "[:mag:](" + buildScanEntry.getValue() + ")");
                    }
                }

                return line;
            }).collect(Collectors.joining("\n"));

            if (buildScansCheckRun.isPresent()) {
                String buildScansLink = "You can consult the [Develocity build scans](" + buildScansCheckRun.get().getHtmlUrl() + ").";

                updatedCommentBody = updatedCommentBody.replace(BUILD_SCANS_CHECK_RUN_MARKER, buildScansLink);
                updatedCommentBody = updatedCommentBody.replace(OLD_BUILD_SCANS_CHECK_RUN_MARKER, buildScansLink);
            }

            if (!updatedCommentBody.equals(reportComment.getBody())) {
                reportComment.update(updatedCommentBody);
            }
        } catch (Exception e) {
            commands.error("Unable to update the PR comment: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Optional<GHIssueComment> getPullRequestComment(Commands commands, GHWorkflowRun workflowRun, GHPullRequest pullRequest) {
        try {
            PullRequestReportIsCreated pullRequestReportIsCreated = new PullRequestReportIsCreated(workflowRun, pullRequest);

            Awaitility.await()
                    .atMost(Duration.ofMinutes(15))
                    .pollDelay(Duration.ofMinutes(2))
                    .pollInterval(Duration.ofMinutes(3))
                    .until(pullRequestReportIsCreated);

            return pullRequestReportIsCreated.getReportComment();
        } catch (ConditionTimeoutException e) {
            commands.warning("Unable to find a report comment to update");
            return Optional.empty();
        }
    }

    private static class PullRequestReportIsCreated implements Callable<Boolean> {

        private final GHWorkflowRun workflowRun;
        private final GHPullRequest pullRequest;

        private GHIssueComment reportComment;

        private PullRequestReportIsCreated(GHWorkflowRun workflowRun, GHPullRequest pullRequest) {
            this.workflowRun = workflowRun;
            this.pullRequest = pullRequest;
        }

        @Override
        public Boolean call() throws Exception {
            List<GHIssueComment> commentsSinceWorkflowRunStarted = new ArrayList<>(pullRequest.queryComments()
                    .since(workflowRun.getCreatedAt())
                    .list().toList());
            Collections.reverse(commentsSinceWorkflowRunStarted);

            String workflowRunIdMarker = String.format(WORKFLOW_RUN_ID_MARKER, workflowRun.getId());
            String oldWorkflowRunIdMarker = String.format(OLD_WORKFLOW_RUN_ID_MARKER, workflowRun.getId());

            Optional<GHIssueComment> reportCommentCandidate = commentsSinceWorkflowRunStarted.stream()
                    .filter(c -> c.getBody().contains(workflowRunIdMarker) || c.getBody().contains(oldWorkflowRunIdMarker))
                    .findFirst();

            if (reportCommentCandidate.isEmpty()) {
                return false;
            }

            reportComment = reportCommentCandidate.get();

            return true;
        }

        public Optional<GHIssueComment> getReportComment() {
            return Optional.ofNullable(reportComment);
        }
    }

    private Optional<GHCheckRun> createBuildScansOutput(Commands commands, GHWorkflowRun workflowRun, BuildScanStatuses statuses) {
        try {
            Output output = new Output(BUILD_SCANS, "You will find below links to the Develocity build scans for this workflow run.");

            StringBuilder buildScans = new StringBuilder();
            buildScans.append("| Status | Name | Build scan |\n");
            buildScans.append("| :-:  | --  | :-:  |\n");

            for (BuildScanStatus build : statuses.builds) {
                buildScans.append("| ").append(getConclusionEmoji(build.buildFailure)).append(" | ").append(build.jobName)
                        .append(" | [:mag:](").append(build.buildScanLink).append(") |\n");
            }

            output.withText(buildScans.toString());

            return Optional.of(workflowRun.getRepository().createCheckRun(BUILD_SCANS, workflowRun.getHeadSha())
                    .add(output)
                    .withConclusion(GHCheckRun.Conclusion.NEUTRAL)
                    .withCompletedAt(new Date())
                    .create());
        } catch (Exception e) {
            commands.error("Unable to create a check run with build scans: " + e.getMessage());
            e.printStackTrace();
            return Optional.empty();
        }
    }

    private static String getConclusionEmoji(Boolean buildFailure) {
        if (buildFailure == null) {
            return ":question:";
        }

        if (buildFailure) {
            return "✖";
        }

        return ":heavy_check_mark:";
    }

    public static class BuildScanStatuses {

        public int prNumber;

        public TreeSet<BuildScanStatus> builds;
    }

    public static class BuildScanStatus implements Comparable<BuildScanStatus> {

        public String jobName;

        public Boolean buildFailure;

        public String buildScanLink;

        @Override
        public int compareTo(BuildScanStatus o) {
            int order1 = getOrder(jobName);
            int order2 = getOrder(o.jobName);

            if (order1 == order2) {
                return jobName.compareToIgnoreCase(o.jobName);
            }

            return order1 - order2;
        }

        @Override
        public String toString() {
            return "BuildScanStatus [jobName=" + jobName + "]";
        }
    }

    private static int getOrder(String jobName) {
        if (jobName.startsWith("Initial JDK")) {
            return 1;
        }
        if (jobName.startsWith("Calculate Test Jobs")) {
            return 2;
        }
        if (jobName.startsWith("JVM Tests - ")) {
            if (jobName.contains("Windows")) {
                return 12;
            }
            return 11;
        }
        if (jobName.startsWith("Maven Tests - ")) {
            if (jobName.contains("Windows")) {
                return 22;
            }
            return 21;
        }
        if (jobName.startsWith("Gradle Tests - ")) {
            if (jobName.contains("Windows")) {
                return 32;
            }
            return 31;
        }
        if (jobName.startsWith("Devtools Tests - ")) {
            if (jobName.contains("Windows")) {
                return 42;
            }
            return 41;
        }
        if (jobName.startsWith("Kubernetes Tests - ")) {
            if (jobName.contains("Windows")) {
                return 52;
            }
            return 51;
        }
        if (jobName.startsWith("Quickstarts Compilation")) {
            return 61;
        }
        if (jobName.startsWith("MicroProfile TCKs Tests")) {
            return 71;
        }
        if (jobName.startsWith("Native Tests - ")) {
            if (jobName.contains("Windows")) {
                return 82;
            }
            return 81;
        }

        return 200;
    }
}
