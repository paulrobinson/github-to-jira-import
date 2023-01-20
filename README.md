# github-to-jira
This script imports issues from GitHub and copies them into JIRA. It also links back to the GitHub issue, from the JIRA issue using the 'GH Pull Request' field. 

**WARNING:** This script was written specifically for the Quarkus project and was not writen with other projects in mind. Therefore there will be some parts of the code that only apply to the Quarkus process. For example, the `QUARKUS` project code is not configurable (yet) and the mapping from GH Issue type (via an `area/*` label) to the JIRA issue type is very Quarkus specific. 


# Pre-requisits
This script is written in Java and is ran by JBang, which makes it much easier to develop and run. See here for how to (easily) install JBang: https://www.jbang.dev/download

# Run the script
Before you run the script, you should edit the `queries.yaml` file. This configuration states which GH issues to import (via the GH query) and what JIRA version to set the imported issues to. The JIRA project is currently hard coded to QUARKUS, as this script initiated as a personal scrpt, and that field did not need parametrising.  

Here's an example command line, for running the script:

    jbang run.java --jira-server https://issues.redhat.com -j <JIRA personel access token> -g <GitHub token with read access> -c ./queries.yaml --rate-limit-delay 500 --dryrun

Notice the `--dryrun` option. With this flag set, the script will only log its intentions, but not actually execute the changes. I.e it won't create any JIRA issues.

The `--rate-limit-delay` option introduces a delay between JIRA requests. This can prevent JIRA blocking requests from exceeding request quotas. The value you set the option to, is the number of millisecods to delay between requests. E.g a value of `500` will uintorduce a half-second delay between requests.

# Manual Steps
After running the script, you will need to manually apply the following changes in JIRA. Maybe one day these will be automated by the scripts. PR's welcome ;-)

1. Run the following JIRA Query to obtain all the issues you just imported: `project = QUARKUS AND created >= -60m AND reporter in ("<JIRA account email address>") ORDER by created DESC`

2. Bulk update the returned issues to: set reporter to `probinso_jira`; add component `team/eng`

3. Bulk update the returned issues to: transition starte to `Dev Complete`

4. JIRA search for `project = QUARKUS AND issuetype = Bug AND fixVersion = <version> AND (summary ~ bump OR summary ~ "update" OR summary ~ upgrade)` and change the issue type to `Component Upgrade` for all issues that look like a dependency upgrade.

# Local dev
Load the code in IntelliJ (or your specified IDE) for editing and live reloading dependency changes

    jbang edit --open=idea --live run.java

# FAQ

## I don't want to import using my personal JIRA account, Can I create a service account?
You can go to https://developer.redhat.com and create a new account, just for use by this script. You will then need to login to JIRA to create a personel access token. 

## Is the script idempotent?
Yes. You can run it several times and it will not cause duplicate issues. This is because the 'GH Pull Request'' field is used to detect any existing imported issue.

