//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.2.0, org.kohsuke:github-api:1.112, com.atlassian.jira:jira-rest-java-client-api:3.0.0, com.atlassian.jira:jira-rest-java-client-core:3.0.0, org.json:json:20200518, com.konghq:unirest-java:3.7.04

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.BasicIssue;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.SearchResult;
import com.atlassian.jira.rest.client.api.domain.Version;
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

    @CommandLine.Option(names = {"-j", "--jira-server"}, description = "The JIRA server to connect to", required = true)
    private String jiraServerURL;

    @CommandLine.Option(names = {"-u", "--username"}, description = "The username to use when connecting to the JIRA server", required = true)
    private String jiraUsername;

    @CommandLine.Option(names = {"-p", "--password"}, description = "The password to use when connecting to the JIRA server", required = true)
    private String jiraPassword;

    @CommandLine.Option(names = {"-t", "--gh-token"}, description = "The GitHub API token to use when connecting to the GitHub API", required = true)
    private String githubToken;

    @CommandLine.Option(names = {"-c", "--config"}, description = "The config file to load the query to version mappings from", required = true)
    private String pathToConfigFile;

    @CommandLine.Option(names = {"-d", "--dryrun"}, description = "Don't actually create issues, just log to the console what the script would have done", required = false)
    private Boolean dryRun = false;

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
        final JiraRestClient restClient = new AsynchronousJiraRestClientFactory().createWithBasicHttpAuthentication(new URI(jiraServerURL), jiraUsername, jiraPassword);
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
                BasicIssue basicIssue =  restClient.getIssueClient().createIssue(issueInput).claim();
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

        for (GHLabel label : issue.getLabels()) {
            switch (label.getName()) {
                case "kind/epic":
                    return 16; // Epic
                case "kind/new-feature":
                    return 2; // Feature Request
                case "kind/bug":
                    return 1; // Bug
                case "kind/question":
                    return 3; //Task
                case "kind/enhancement":
                    return 2; // Feature Request
                case "kind/extension-proposal":
                    return 2; // Feature Request
            }
        }
        return 1; //Bug (Default);  
    }

    private String lookupIssueWithGithubLink(JiraRestClient restClient, String githubLink) {
        SearchResult searchResults = restClient.getSearchClient().searchJql("project = " + JIRA_PROJECT_CODE + " AND issuetype in (Bug, 'Feature Request', Task) AND 'Git Pull Request' ~ '" + githubLink + "'").claim();

        String results = "";
        Iterator<Issue> iterator = searchResults.getIssues().iterator();
        while (iterator.hasNext()) {
            String issueKey = iterator.next().getKey();
            results += jiraServerURL + "/browse/" +  issueKey + " ";
        }
        return results;
    }

    private String lookupVersionId(String versionName) {

        try {
            URL urlToCall = new URL(jiraServerURL + "/rest/api/2/project/" + JIRA_PROJECT_CODE + "/versions");
            String userpass = jiraUsername + ":" + jiraPassword;
            String basicAuth = "Basic " + new String(Base64.getEncoder().encode(userpass.getBytes()));

            URLConnection urlConnection = urlToCall.openConnection();
            urlConnection.setRequestProperty("Authorization", basicAuth);
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
}
