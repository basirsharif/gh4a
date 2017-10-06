package com.gh4a.resolver;

import android.content.Intent;
import android.support.annotation.VisibleForTesting;
import android.support.v4.app.FragmentActivity;

import com.gh4a.Gh4Application;
import com.gh4a.activities.ReviewActivity;
import com.gh4a.loader.TimelineItem;
import com.gh4a.utils.ApiHelpers;
import com.gh4a.utils.IntentUtils;
import com.meisolsson.githubsdk.model.Page;
import com.meisolsson.githubsdk.model.Review;
import com.meisolsson.githubsdk.model.ReviewComment;
import com.meisolsson.githubsdk.service.pull_request.PullRequestReviewCommentService;
import com.meisolsson.githubsdk.service.pull_request.PullRequestReviewService;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PullRequestReviewCommentLoadTask extends UrlLoadTask {
    @VisibleForTesting
    protected final String mRepoOwner;
    @VisibleForTesting
    protected final String mRepoName;
    @VisibleForTesting
    protected final int mPullRequestNumber;
    @VisibleForTesting
    protected final IntentUtils.InitialCommentMarker mMarker;

    public PullRequestReviewCommentLoadTask(FragmentActivity activity, String repoOwner,
            String repoName, int pullRequestNumber, IntentUtils.InitialCommentMarker marker,
            boolean finishCurrentActivity) {
        super(activity, finishCurrentActivity);
        mRepoOwner = repoOwner;
        mRepoName = repoName;
        mPullRequestNumber = pullRequestNumber;
        mMarker = marker;
    }

    @Override
    protected Intent run() throws Exception {
        final Gh4Application app = Gh4Application.get();
        final PullRequestReviewService reviewService =
                app.getGitHubService(PullRequestReviewService.class);
        final PullRequestReviewCommentService commentService =
                app.getGitHubService(PullRequestReviewCommentService.class);

        List<ReviewComment> comments = ApiHelpers.Pager.fetchAllPages(
                new ApiHelpers.Pager.PageProvider<ReviewComment>() {
            @Override
            public Page<ReviewComment> providePage(long page) throws IOException {
                return ApiHelpers.throwOnFailure(commentService.getPullRequestComments(
                        mRepoOwner, mRepoName, mPullRequestNumber, page).blockingGet());
            }
        });

        // Required to have comments sorted so we can find correct review
        Collections.sort(comments, ApiHelpers.COMMENT_COMPARATOR);

        Map<String, ReviewComment> commentsByDiffHunkId = new HashMap<>();
        for (ReviewComment comment : comments) {
            String id = TimelineItem.Diff.getDiffHunkId(comment);

            if (!commentsByDiffHunkId.containsKey(id)) {
                // Because the comment we are looking for could be a reply to another review
                // we have to keep track of initial comments for each diff hunk
                commentsByDiffHunkId.put(id, comment);
            }

            if (mMarker.matches(comment.id(), null)) {
                // Once found the comment we are looking for get a correct review id from
                // the initial diff hunk comment
                ReviewComment initialComment = commentsByDiffHunkId.get(id);
                long reviewId = initialComment.pullRequestReviewId();

                Review review = ApiHelpers.throwOnFailure(reviewService.getReview(
                        mRepoOwner, mRepoName, mPullRequestNumber, reviewId).blockingGet());
                return ReviewActivity.makeIntent(mActivity, mRepoOwner, mRepoName,
                        mPullRequestNumber, review, mMarker);
            }
        }

        return null;
    }
}
