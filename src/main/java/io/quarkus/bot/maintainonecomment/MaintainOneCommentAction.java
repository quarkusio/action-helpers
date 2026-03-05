package io.quarkus.bot.maintainonecomment;

import java.io.IOException;
import java.util.Optional;

import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

import io.quarkiverse.githubaction.Action;
import io.quarkiverse.githubaction.Context;
import io.quarkiverse.githubaction.Inputs;

public class MaintainOneCommentAction {
    private static final String DEFAULT_BODY_MARKER = "<!-- Sticky Comment -->";

    @Action("maintain-one-comment")
    void maintainOneComment(Context context, Inputs inputs, GitHub gitHub) throws IOException {
        String body = inputs.getRequired("body");
        String bodyMarker = inputs.get("body-marker").orElse(DEFAULT_BODY_MARKER);
        int prNumber = inputs.getRequiredInt("pr-number");

        GHRepository repository = gitHub.getRepository(context.getGitHubRepository());
        GHPullRequest pullRequest = repository.getPullRequest(prNumber);

        Optional<GHIssueComment> maintainedComment = pullRequest.listComments().toList().stream()
                .filter(c -> c.getBody() != null && c.getBody().contains(bodyMarker))
                .findFirst();

        String markedBody = body + "\n\n" + bodyMarker;

        if (maintainedComment.isEmpty()) {
            pullRequest.comment(markedBody);
        } else {
            maintainedComment.get().update(markedBody);
        }
    }
}
