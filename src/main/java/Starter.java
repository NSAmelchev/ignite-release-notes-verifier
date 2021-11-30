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

/** */
public class Starter {
    /** */
    private final static String JIRA_FIX_VERSION = "2.12";

    /** */
    private final static String GIT_PATH = "/Users/home/work/ignite";

    /** */
    private final static String RELEASE_BRANCH = "apache/ignite-2.12";

    /** */
    private final static String RELEASED_BRANCH = "apache/ignite-2.11";

    /** */
    public static void main(String[] args) throws Exception {
        Map<String, JiraIssue> jira = jiraIssues();

        System.out.println("Jira issues count = " + jira.size());

        Set<RevCommit> commits = releaseCommits();

        System.out.println("Commit count = " + commits.size());

        Pattern reg = Pattern.compile("(IGNITE-\\d+):? (.*)");

        List<RevCommit> unknownCommits = new LinkedList<>();

        HashMap<String, String> map = new HashMap<>();

        for (RevCommit commit : commits) {
            Matcher matcher = reg.matcher(commit.getShortMessage());

            if (matcher.matches()) {
                String jiraKey = matcher.group(1);
                String summary = matcher.group(2);

                map.put(jiraKey, summary);
            }
            else
                unknownCommits.add(commit);
        }


        System.out.println();
        System.out.println("Unknown JIRA issues:");
        jira.values().stream().filter(issue -> !map.containsKey(issue.key))
            .forEach(issue -> System.out.println("https://issues.apache.org/jira/browse/" + issue.key + " "
                + issue.summary + " " + issue.status));

        System.out.println();
        System.out.println("RELEASE NOTES:");
        map.forEach((s, s2) -> System.out.println(s2));

        System.out.println();
        System.out.println("Unknown commits:");
        unknownCommits.forEach(commit -> System.out.println(commit.getName() + " " + commit.getShortMessage()));
    }

    /** */
    private static Set<RevCommit> releaseCommits() throws Exception {
        Git git = Git.open(new File(GIT_PATH));

        Iterable<RevCommit> commits = git.log().add(git.getRepository().resolve(RELEASE_BRANCH)).call();
        Iterable<RevCommit> commitsReleased = git.log().add(git.getRepository().resolve(RELEASED_BRANCH)).call();

        Set<RevCommit> excluded = new TreeSet<>();

        for (RevCommit commit : commitsReleased)
            excluded.add(commit);

        Set<RevCommit> res = new TreeSet<>();

        for (RevCommit commit : commits) {
            if (excluded.contains(commit))
                continue;

            res.add(commit);
        }

        return res;
    }

    /** */
    private static Map<String, JiraIssue> jiraIssues() throws IOException {
        URL url = new URL("https://issues.apache.org/jira/rest/api/2/search?" +
            "jql=project+%3D+IGNITE+AND+fixVersion+%3D+" + JIRA_FIX_VERSION +
            // exclude documentation issues
            "%20AND%20(component%20is%20EMPTY%20OR%20component%20not%20in%20(documentation))" +
            "&maxResults=10000");

        System.out.println(url.toString());

        URLConnection conn = url.openConnection();

        conn.connect();

        ObjectMapper mapper = new ObjectMapper();

        JsonNode json = mapper.readTree(conn.getInputStream());

        ArrayNode issues = (ArrayNode)json.get("issues");

        Map<String, JiraIssue> res = new HashMap<>();

        for (JsonNode issue : issues) {
            res.put(
                issue.get("key").asText(),
                new JiraIssue(
                    issue.get("key").asText(),
                    issue.get("fields").get("summary").asText(),
                    issue.get("fields").get("status").get("name").asText()
                )
            );
        }

        return res;
    }

    /** */
    static class JiraIssue {
        final String key;

        final String summary;

        final String status;

        JiraIssue(String key, String summary, String status) {
            this.key = key;
            this.summary = summary;
            this.status = status;
        }
    }
}
