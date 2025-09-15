<a href="https://opensource.newrelic.com/oss-category/#new-relic-experimental"><picture><source media="(prefers-color-scheme: dark)" srcset="https://github.com/newrelic/opensource-website/raw/main/src/images/categories/dark/Experimental.png"><source media="(prefers-color-scheme: light)" srcset="https://github.com/newrelic/opensource-website/raw/main/src/images/categories/Experimental.png"><img alt="New Relic Open Source experimental project banner." src="https://github.com/newrelic/opensource-website/raw/main/src/images/categories/Experimental.png"></picture></a>

![GitHub forks](https://img.shields.io/github/forks/newrelic-experimental/newrelic-java-pmicollector?style=social)
![GitHub stars](https://img.shields.io/github/stars/newrelic-experimental/newrelic-java-pmicollector?style=social)
![GitHub watchers](https://img.shields.io/github/watchers/newrelic-experimental/newrelic-java-pmicollector?style=social)

![GitHub all releases](https://img.shields.io/github/downloads/newrelic-experimental/newrelic-java-pmicollector/total)
![GitHub release (latest by date)](https://img.shields.io/github/v/release/newrelic-experimental/newrelic-java-pmicollector)
![GitHub last commit](https://img.shields.io/github/last-commit/newrelic-experimental/newrelic-java-pmicollector)
![GitHub Release Date](https://img.shields.io/github/release-date/newrelic-experimental/newrelic-java-pmicollector)


![GitHub issues](https://img.shields.io/github/issues/newrelic-experimental/newrelic-java-pmicollector)
![GitHub issues closed](https://img.shields.io/github/issues-closed/newrelic-experimental/newrelic-java-pmicollector)
![GitHub pull requests](https://img.shields.io/github/issues-pr/newrelic-experimental/newrelic-java-pmicollector)
![GitHub pull requests closed](https://img.shields.io/github/issues-pr-closed/newrelic-experimental/newrelic-java-pmicollector)

# New Relic Java Instrumentation for WebSphere PMI Collection

Provides instrumentation that reports PMI data from WebSphere as metrics and/or custom events.



## Installation

This use this instrumentation.
1. Download the latest release.
2. Edit newrelic.yml and disable websphere-jmx-7 instrumentation module in the agent (see below).   
3. In the New Relic Java Agent directory (directory containing newrelic.jar), create a directory named extensions if it doe not already exist. 
4. Copy the jars into the extensions directory. 
5. Restart the application.
   
### Disable websphere-jmx-7
1. Edit newrelic.yml
2. Find the following in newrelic.yml (note that not all of the enables below may be present.)   
&nbsp;&nbsp;class_transformer:   
&nbsp;&nbsp;      
&nbsp;&nbsp;&nbsp;&nbsp;com.newrelic.instrumentation.servlet-user:   
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;enabled: false    
&nbsp;&nbsp;   
&nbsp;&nbsp;&nbsp;&nbsp;com.newrelic.instrumentation.spring-aop-2:   
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;enabled: false   
&nbsp;&nbsp;   
&nbsp;&nbsp;&nbsp;&nbsp;\# This instrumentation reports metrics for resultset operations.   
&nbsp;&nbsp;&nbsp;&nbsp;com.newrelic.instrumentation.jdbc-resultset:   
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;enabled: false   
   
3. Add the following lines after the above to disable websphere-jmx-7    
&nbsp;&nbsp;&nbsp;&nbsp;com.newrelic.instrumentation.websphere-jmx-7:   
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;enabled: false   
   
## Getting Started
    
After install the extension, the collector will report enabled PMI values as per the configuration described below.   
   
## Metric Naming
PMI metrics are named using the following   
PMI/**process_name**/**node_name**/**cell**/**wsname**/**stat_name**   
   
or if the process, node and cell are not defined the name is   
PMI/**wsname**/**stat_name**

## Configuration   
The collector can be configured to report enabled PMI values as metrics or custom events or both.   It can also be disabled.  All configuration values are dynamic so you can change them in newrelic.yml and the changes will take effect within a minute or so without restarting the application.  
The PMICollector is configured in newrelic.yml.  
### Settings
There are five settings for the PMICollector.   

| Setting | Description                                            | Value Type    | Default              |   
|---------|--------------------------------------------------------|---------------|----------------------|  
| enabled | Whether collector is reporting data or not             | true or false | true                 |
| metrics_enabled | Whether collector is reporting data as metrics         | true or false | false                |
| events_enabled | Whether to send PMI data as custom events              | true or false | true                 |
| stat_type_filter | Only report based on statType                          | comma separate list of stat types to report | no filter report all |
| name_filter | Only report based on metric name or stat name          | comma separate list of name to report | no filter report all |
| debug   | Enable detailed logging to agent log and custom events | true or false | false                |

## Custom Events
If custom events are enabled the following custom events are created and sent to New Relic.    

| Event Name | Description                                                 | 
| ---------- |-------------------------------------------------------------|
| PMIInitialization | Reports info related to the initialization of the Collector |
| PMIEvent | Reports the values of a PMI statatic if enabled (default)   |
    
### Debug Custom Events
| Event Name | Description                                            | 
| ---------- |--------------------------------------------------------|
| ProcessStat | Reports info related to values used to name the metric |

## Building

If you make changes to the instrumentation code and need to build the instrumentation jars, follow these steps
1. Set environment variable NEW_RELIC_EXTENSIONS_DIR.  Its value should be the directory where you want to build the jars (i.e. the extensions directory of the Java Agent).
2. Build one or all of the jars.   
   a. To build one jar, run the command:  gradlew _moduleName_:clean  _moduleName_:install    
   b. To build all jars, run the command: gradlew clean install
3. Restart the application

## Support

New Relic has open-sourced this project. This project is provided AS-IS WITHOUT WARRANTY OR DEDICATED SUPPORT. Issues and contributions should be reported to the project here on GitHub.

>We encourage you to bring your experiences and questions to the [Explorers Hub](https://discuss.newrelic.com) where our community members collaborate on solutions and new ideas.

## Contributing

We encourage your contributions to improve Salesforce Commerce Cloud for New Relic Browser! Keep in mind when you submit your pull request, you'll need to sign the CLA via the click-through using CLA-Assistant. You only have to sign the CLA one time per project. If you have any questions, or to execute our corporate CLA, required if your contribution is on behalf of a company, please drop us an email at opensource@newrelic.com.

**A note about vulnerabilities**

As noted in our [security policy](../../security/policy), New Relic is committed to the privacy and security of our customers and their data. We believe that providing coordinated disclosure by security researchers and engaging with the security community are important means to achieve our security goals.

If you believe you have found a security vulnerability in this project or any of New Relic's products or websites, we welcome and greatly appreciate you reporting it to New Relic through [HackerOne](https://hackerone.com/newrelic).

## License

New Relic Java Instrumentation for WebSphere PMI is licensed under the [Apache 2.0](http://apache.org/licenses/LICENSE-2.0.txt) License.
