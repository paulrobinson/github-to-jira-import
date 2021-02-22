# github-to-jira
This script imports issues from GitHub and copies them into JIRA. It also links back to the GitHub issue, from the JIRA issue using the 'GH Pull Request' field. 

**WARNING:** This script was written specifically for the Quarkus project and was not writen with other projects in mind. Therefore there will be some parts of the code that only apply to the Quarkus process. For example, the `QUARKUS` project code is not configurable (yet) and the mapping from GH Issue type (via an `area/*` label) to the JIRA issue type is very Quarkus specific. 


# Pre-requisits
This script is written in Java and is ran by JBang, which makes it much easier to develop and run. See here for how to (easily) install JBang: https://www.jbang.dev/download

# Run the script
Before you run the script, you should edit the `queries.yaml` file. This configuration states which GH issues to import (via the GH query) and what JIRA version to set the imported issues to. The JIRA project is currently hard coded to QUARKUS, as this script initiated as a personal scrpt, and that field did not need parametrising.  

Here's an example command line, for running the script:

    jbang run.java --jira-server https://issues.redhat.com -u <JIRA username> -p <JIRA password> -t <GitHub token with read access> -c ./queries.yaml --dryrun

Notice the `--dryrun` option. With this flag set, the script will only log its intentions, but not actually execute the changes.

# Local dev
Load the code in IntelliJ (or your specified IDE) for editing and live reloading dependency changes

    jbang edit --open=idea --live run.java

# FAQ

## I don't want to import using my personal JIRA account, Can I create a service account?
You can go to https://developer.redhat.com and create a new account, just for use by this script. You might need to login to JIRA initially via the web, before the REST API will accept these new credentials. 

## Is the script idempotent?
Yes. You can run it several times and it will not cause duplicate issues. This is because the 'GH Pull Request'' field is used to detect an existing imported issue.

## Can the script be run via a GitHub action?
Yes! There is all the code and config needed here to run this as a GitHub action. However, it's not yet documented. Also, the code might be stale, as I ended up just runnign this on demand at key points in the Quarkus productisation process. 
