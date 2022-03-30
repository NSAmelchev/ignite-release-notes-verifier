/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;

/**
 * 1. Use correct versions and paths.
 * 2. Make sure that release branches are actual.
 * 3. Run {@link #main(String[])}
 */
public class Starter {
    /** Ignite git path. */
    private final static String GIT_PATH = "/Users/some-user/work/ignite";

    /** Jira fix version tag. */
    public final static String JIRA_FIX_VERSION = "2.13";

    /** Release branch. */
    private final static String RELEASE_BRANCH = "apache/ignite-2.13";

    /** Previously released branch. */
    private final static String RELEASED_BRANCH = "apache/ignite-2.12";

    /** */
    public static void main(String[] args) throws Exception {
        Map<String, JiraIssue> jira = jiraIssues();
        Map<String, String> git = gitIssues();

        System.out.println("Jira issues count: " + jira.size());
        System.out.println("Release commit count: " + git.size());

        System.out.println();
        System.out.println("Unknown JIRA issues (does not match commits).");

        jira.values().stream()
            .filter(issue -> !git.containsKey(issue.key))
            .sorted((i1, i2) -> i1.status.compareTo(i2.status))
            .forEach(issue -> System.out.println('[' + issue.status + "] " + issue.summary + " https://issues.apache.org/jira/browse/" + issue.key));

        System.out.println();
        System.out.println("Release notes to verify:");

        git.entrySet().stream().map(commit -> {
                JiraIssue issue = jira.get(commit.getKey());
                String commitMsg = commit.getValue();

                if (issue == null)
                    return "[WARN: CHECK ISSUE] " + commitMsg + " https://issues.apache.org/jira/browse/" + commit.getKey();

                String msg;

                if (issue.releaseNotesRequired && issue.releaseNote.length() == 0)
                    msg = "[ERROR: NO RELEASE NOTE] " + commitMsg;
                else if (!issue.releaseNotesRequired && issue.releaseNote.length() > 0)
                    msg = "[ERROR: NO RELEASE NOTE FLAG]";
                else if (!issue.releaseNotesRequired && issue.releaseNote.length() == 0)
                    msg = "[NOTES NOT REQUIRED] " + commitMsg;
                else
                    msg = "[OK] " + issue.releaseNote + "\n\t" + commitMsg;

                msg += " | https://issues.apache.org/jira/browse/" + issue.key + " " + issue.summary;

                return msg;
            })
            .sorted()
            .forEach(s -> System.out.println(s));

        System.out.println();
        System.out.println("RELEASE NOTES:");

        git.keySet().stream().map(issue -> jira.get(issue))
            .filter(issue -> issue != null && issue.releaseNotesRequired)
            .map(issue -> issue.releaseNote)
            // Trim.
            .map(s -> s.trim())
            // Fix dots.
            .map(releaseNote -> releaseNote.endsWith(".") ? releaseNote : releaseNote + '.')
            // Sort alphabetically.
            .sorted()
            .forEach(releaseNote -> System.out.println(releaseNote));
    }

    /** */
    private static Map<String, String> gitIssues() throws Exception {
        Set<RevCommit> commits = releaseCommits();

        Pattern reg = Pattern.compile("(IGNITE-\\d+):? (.*)");

        List<RevCommit> unknownCommits = new LinkedList<>();

        Map<String, String> parsedCommits = new HashMap<>();

        for (RevCommit commit : commits) {
            Matcher matcher = reg.matcher(commit.getShortMessage());

            if (matcher.matches()) {
                String issue = matcher.group(1);
                String summary = matcher.group(2);

                parsedCommits.put(issue, summary);
            }
            else
                unknownCommits.add(commit);
        }

        if (unknownCommits.size() > 0) {
            System.out.println();
            System.out.println("Unknown commits (does not match Jira issues). Make sure that no jira issue needed.");
            unknownCommits.forEach(commit -> System.out.println(commit.getShortMessage() + " [hash=" + commit.getName() + ']'));
            System.out.println();
        }

        return parsedCommits;
    }

    /** @return Release commits. */
    private static Set<RevCommit> releaseCommits() throws Exception {
        Git git = Git.open(new File(GIT_PATH));

        Iterable<RevCommit> commits = git.log().add(git.getRepository().resolve(RELEASE_BRANCH)).call();
        Iterable<RevCommit> commitsReleased = git.log().add(git.getRepository().resolve(RELEASED_BRANCH)).call();

        Set<RevCommit> excluded = new TreeSet<>();
        Set<String> excludedMsgs = new TreeSet<>();

        for (RevCommit commit : commitsReleased) {
            excluded.add(commit);
            excludedMsgs.add(commit.getShortMessage());
        }

        Set<RevCommit> res = new TreeSet<>();

        for (RevCommit commit : commits) {
            if (excluded.contains(commit) || excludedMsgs.contains(commit.getShortMessage()))
                continue;

            res.add(commit);
        }

        if (res.size() == 0)
            throw new RuntimeException("Bad request.");

        return res;
    }

    /** @return Release jira issues (documentation excluded). */
    private static Map<String, JiraIssue> jiraIssues() throws IOException {
        URL url = new URL("https://issues.apache.org/jira/rest/api/2/search?" +
            // project=IGNITE AND fixVersion=JIRA_FIX_VERSION
            "jql=project+%3D+IGNITE+AND+fixVersion+%3D+" + JIRA_FIX_VERSION +
            // exclude documentation and extensions issues
            "%20AND%20(component%20is%20EMPTY%20OR%20component%20not%20in%20(documentation%2C%20extensions))" +
            // max results per page (1000 is max available by API)
            "&maxResults=1000");

        URLConnection conn = url.openConnection();

        conn.connect();

        JsonNode json = new ObjectMapper().readTree(conn.getInputStream());

        ArrayNode issues = (ArrayNode)json.get("issues");

        if (issues.size() > 990)
            throw new RuntimeException("Multi-pages requests are not supported.");

        Map<String, JiraIssue> res = new HashMap<>();

        for (JsonNode issue : issues) {
            boolean releaseNotesRequired = false;

            for (JsonNode flag : issue.get("fields").get("customfield_12313620")) {
                if (flag.get("value").asText().equals("Release Notes Required"))
                    releaseNotesRequired = true;
            }

            res.put(
                issue.get("key").textValue(),
                new JiraIssue(
                    issue.get("key").textValue(),
                    issue.get("fields").get("summary").textValue(),
                    issue.get("fields").get("status").get("name").textValue(),
                    releaseNotesRequired,
                    issue.get("fields").get("customfield_12310192").textValue())
            );
        }

        if (res.size() == 0)
            throw new RuntimeException("Bad request.");

        return res;
    }

    /** */
    static class JiraIssue {
        /** */
        final String key;

        /** */
        final String summary;

        /** */
        final String status;

        /** */
        final boolean releaseNotesRequired;

        /** */
        final String releaseNote;

        /** */
        JiraIssue(String key, String summary, String status, boolean releaseNotesRequired, String releaseNote) {
            this.key = key;
            this.summary = summary;
            this.status = status;
            this.releaseNotesRequired = releaseNotesRequired;
            this.releaseNote = releaseNote == null ? "" : releaseNote;
        }
    }
}
