//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.2.0, org.kohsuke:github-api:1.112, com.atlassian.jira:jira-rest-java-client-api:5.2.2, com.atlassian.jira:jira-rest-java-client-app:5.2.2, org.json:json:20200518, com.konghq:unirest-java:3.7.04

//REPOS mavencentral,atlassian=https://packages.atlassian.com/maven/repository/public

import com.atlassian.httpclient.api.Request;
import com.atlassian.jira.rest.client.api.AuthenticationHandler;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.RestClientException;
import com.atlassian.jira.rest.client.api.domain.*;
import com.atlassian.jira.rest.client.api.domain.input.FieldInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import com.atlassian.jira.rest.client.internal.json.JsonParseUtil;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.kohsuke.github.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "run", mixinStandardHelpOptions = true, version = "run 0.1",
        description = "GitHub to Jira issue replicator")
class run implements Callable<Integer> {

    @CommandLine.Option(names = {"-j", "--jira-token"}, description = "The Personal Access Token for authenticating with the JIRA server", required = true)
    private String jiraToken;

    @CommandLine.Option(names = {"-s", "--jira-server"}, description = "The JIRA server to connect to", required = true, defaultValue = "https://issues.redhat.com")
    private String jiraServerURL;

    @CommandLine.Option(names = {"-g", "--gh-token"}, description = "The GitHub API token to use when connecting to the GitHub API", required = true)
    private String githubToken;

    @CommandLine.Option(names = {"-c", "--config"}, description = "The config file to load the query to version mappings from", required = true, defaultValue = "queries.yaml")
    private String pathToConfigFile;

    @CommandLine.Option(names = {"-d", "--dryrun"}, description = "Don't actually create issues, just log to the console what the script would have done", required = false)
    private Boolean dryRun = false;

    @CommandLine.Option(names = {"-r", "--rate-limit-delay"}, description = "Basic rate limiting. Specify number of milliseconds to delay between JIRA issue create requests", required = false)
    private Integer rateLimitDelay = 0;

    //TODO: need to infer this, or make it confgurable for other projects to use the script
    private static final String JIRA_PROJECT_CODE = "QUARKUS";
    private static final String JIRA_GIT_PULL_REQUEST_FIELD_ID = "customfield_12310220";

    public static void main(String... args) {
        int exitCode = new CommandLine(new run()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {

        /*
            Initialise
         */
        Map<String, String> configuration = loadQueryToVersionMap(pathToConfigFile);
        final JiraRestClient restClient = new AsynchronousJiraRestClientFactory().create(new URI(jiraServerURL), new BearerHttpAuthenticationHandler(jiraToken));
        GitHub githubClient = new GitHubBuilder().withOAuthToken(githubToken).build();

        /*
            Loop over GitHub queries to export and do the exporting to JIRA
         */
        for (String githubQuery : configuration.keySet()) {

            String jiraVersionName = configuration.get(githubQuery);
            exportFromGithubQueryToJIRA(githubClient, restClient, githubQuery, jiraVersionName);
        }

        return 0;
    }

    private void exportFromGithubQueryToJIRA(GitHub gitHubClient,  JiraRestClient restClient, String githubQuery, String jiraVersionName) throws Exception {

        URI versionQueryURI = new URI(jiraServerURL + "/rest/api/2/version/" + lookupVersionId(jiraVersionName));
        Version jiraVersion = restClient.getVersionRestClient().getVersion(versionQueryURI).claim();

        System.out.println("Importing from GitHub Query: '" + githubQuery + "' to JIRA version ID '" + jiraVersionName + "'");

        List<Version> fixVersions = new LinkedList<>();
        fixVersions.add(jiraVersion);

        GHIssueSearchBuilder searchBuilder = gitHubClient.searchIssues().q(githubQuery);

        System.out.println("Found " + searchBuilder.list().toSet().size() + " issues to import");

        for (GHIssue ghIssue : searchBuilder.list()) {

            String existingIssues = lookupIssueWithGithubLink(restClient, ghIssue.getHtmlUrl().toString());
            if (!existingIssues.equals("")) {
                System.out.println("Issue(s) already exist with this GitHub Link: " + ghIssue.getHtmlUrl() + ". See: " + existingIssues);
                continue;
            }

            long issueTypeID = getJIRAIssueTypeID(ghIssue);
            IssueInputBuilder issueBuilder = new IssueInputBuilder(JIRA_PROJECT_CODE, issueTypeID, ghIssue.getTitle());
            issueBuilder.setDescription(ghIssue.getBody());
            issueBuilder.setFixVersions(fixVersions);
            issueBuilder.setFieldValue(JIRA_GIT_PULL_REQUEST_FIELD_ID, ghIssue.getHtmlUrl().toString());
            IssueInput issueInput = issueBuilder.build();

            if (dryRun) {
                System.out.println("[DRY RUN] CREATE: '" +  ghIssue.getTitle() + "' FROM " + ghIssue.getHtmlUrl());
            } else {
                BasicIssue basicIssue = null;
                try {
                    basicIssue = restClient.getIssueClient().createIssue(issueInput).claim();

                    if (rateLimitDelay > 0) {
                        System.out.println("Waiting for " + rateLimitDelay + "ms to provide basic rate limiting");
                        Thread.sleep(rateLimitDelay);
                    }

                } catch (RestClientException e) {
                    System.out.println("MAYBE CREATED ISSUE: " + ghIssue.getTitle());
                    e.printStackTrace();
                    System.out.println("Exiting so that you can figure out what went wrong with ^^^");
                    System.exit(-1);
                }
                System.out.println("CREATED ISSUE: " + jiraServerURL + "/browse/" +  basicIssue.getKey());
            }
        }
    }

    private Map<String, String> loadQueryToVersionMap(String pathToConfigFile) {
        try {

            String jsonString = new String(Files.readAllBytes(Paths.get(pathToConfigFile)));
            JSONArray jsonArray = new JSONArray(jsonString);

            Map<String, String> queryToVersionMap = new HashMap<>();
            for (int i=0; i<jsonArray.length(); i++) {
                JSONObject configItemJson = jsonArray.getJSONObject(i);
                String query = configItemJson.getString("query");
                String version = configItemJson.getString("version");
                queryToVersionMap.put(query, version);
            }

            return queryToVersionMap;
        } catch (IOException e) {
            throw new RuntimeException("Error loading " + pathToConfigFile);
        }
    }

    private long getJIRAIssueTypeID(GHIssue issue) throws Exception {

        //See https://issues.redhat.com/rest/api/2/project/QUARKUS for all issue type ids

        for (GHLabel label : issue.getLabels()) {
            switch (label.getName()) {
                case "kind/epic":
                    return 16; // Epic https://issues.redhat.com/rest/api/2/issuetype/16
                case "kind/new-feature":
                    return 10700; // Feature https://issues.redhat.com/rest/api/2/issuetype/10700
                case "kind/bug":
                    return 1; // Bug https://issues.redhat.com/rest/api/2/issuetype/1
                case "kind/bug-fix":
                    return 1; // Bug
                case "kind/question":
                    return 3; //Task https://issues.redhat.com/rest/api/2/issuetype/3
                case "kind/enhancement":
                    return 10700; // Feature https://issues.redhat.com/rest/api/2/issuetype/10700
                case "kind/extension-proposal":
                    return 10700; // Feature https://issues.redhat.com/rest/api/2/issuetype/10700
            }
        }
        return 3; //Task
    }

    private String lookupIssueWithGithubLink(JiraRestClient restClient, String githubLink) {
        SearchResult searchResults = restClient.getSearchClient().searchJql("project = " + JIRA_PROJECT_CODE + " AND 'Git Pull Request' ~ '" + githubLink + "'").claim();

        StringBuilder results = new StringBuilder();
        for (Issue issue : searchResults.getIssues()) {
            String issueKey = issue.getKey();
            results.append(jiraServerURL).append("/browse/").append(issueKey).append(" ");
        }
        return results.toString();
    }

    private String lookupVersionId(String versionName) {

        try {
            URL urlToCall = new URL(jiraServerURL + "/rest/api/2/project/" + JIRA_PROJECT_CODE + "/versions");
            String tokenAuth = "Bearer " + new String(Base64.getEncoder().encode(jiraToken.getBytes()));

            URLConnection urlConnection = urlToCall.openConnection();
            urlConnection.setRequestProperty("Authorization", tokenAuth);
            InputStream in = urlConnection.getInputStream();

            StringWriter writer = new StringWriter();
            IOUtils.copy(in, writer);
            String jsonVersionsString = writer.toString();

            JSONArray jsonArray = new JSONArray(jsonVersionsString);
            for (int i = 0; i < jsonArray.length(); i++)
            {
                String name = jsonArray.getJSONObject(i).getString("name");
                if (name.equals(versionName)) {
                    return jsonArray.getJSONObject(i).getString("id");
                }
            }
            throw new RuntimeException("Could not find a version with name: " + versionName);

        } catch (IOException e) {
            throw new RuntimeException("Error looking up versions", e);
        }
    }

    public static class BearerHttpAuthenticationHandler implements AuthenticationHandler {

        private static final String AUTHORIZATION_HEADER = "Authorization";
        private final String token;

        public BearerHttpAuthenticationHandler(final String token) {
            this.token = token;
        }

        @Override
        public void configure(Request.Builder builder) {
            builder.setHeader(AUTHORIZATION_HEADER, "Bearer " + token);
        }
    }
}
