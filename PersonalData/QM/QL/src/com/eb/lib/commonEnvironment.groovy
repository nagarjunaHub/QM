package com.eb.lib
import com.eb.lib.jobUtils
import com.eb.lib.gerritUtils

/************************************************************************************************************
 * Print Map as key-value
 * @param Map projectConfig
 * @param String additional string to print
 * @return void
**/
def printConfiguration(Map configMap, String stringToPrint='') {
    println("---------- PRINT ${stringToPrint} CONFIGURATION ----------")
    for (item in configMap) {
        println("${item.key} --- ${item.value}")
    }
    println("----------------------------------------------------------")
}

/************************************************************************************************************
 * Set up the config's combination among global config <- project <- config
 * @param String projectConfig
 * @return Map
**/
Map setupConfiguration(String projectConfig) {
    // stores loaded configuration
    Map envConf = [:]

    // load global default pipeline configuration from yaml file
    def globalConf = loadConfigurationFromFile()

    // set default job mode on global level according to job name
    globalConf.general.jobName = getJobBase()

    // load optional dynamic pipeline configurations passed as string parameter
    def dynamicConf = params.DYNAMIC_CONFIG ? readYaml(text: params.DYNAMIC_CONFIG) : [:]

    // load variant pipeline configuration from yaml file
    globalConf.scm = getScm(globalConf.general.defaultNode)
    loadProjectLibrary(globalConf.scm)
    
    def projectConf = [:]

    if (!params.DYNAMIC_CONFIG) {
        projectConf = loadConfigurationFromFile(projectConfig?:'eb/project/config.yml')
    }

    // merge all configurations in sequential order: global <- variant <- dynamic
    envConf = new jobUtils().mergeMapRecursive(globalConf, projectConf, dynamicConf)

    // generate environment configuration based on defined job selected configuration
    // iterate over each job: jobConf.key = jobName, jobConf.value = Map of Job specific items
    for (jobConf in envConf?.job) {
        if (jobConf.key.equalsIgnoreCase(envConf.general.jobName)) {
            envConf = new jobUtils().mergeMapRecursive(envConf,jobConf.value)
            envConf.remove('job')
        }
    }
    return envConf
}

/************************************************************************************************************
 * Set up the bash's libraries combination amongs: global bash lib <- project bash lib <- development bash lib
 * 
 * @return String path to final lib
**/
def loadBashLibs() {
    def globalLibFile = 'eb/global/common.lib'
    def projectLibFile = 'eb/project/common.lib'

    def globalLib = ""
    def projectLib = ""
    def devLib = ""
    try {
        globalLib = libraryResource globalLibFile
    } catch(err) {
        println("[INFO] ${globalLibFile} Not Found")
    }
    try{
        projectLib = libraryResource projectLibFile
    } catch(err) {
        println("[INFO] ${projectLibFile} Not Found")
    }
    try{
        devLib = devBashLib.getBashLib()
        println("devBashLib.groovy: ${devLib}")
    } catch(err) {
        println("[INFO] devBashLib.groovy Not Found, Enable By Adding To \'vars/\'")
    }
    writeFile file: "${env.WORKSPACE}/BASH_LIBRARY.lib", text: globalLib + "\n" + projectLib + "\n" + devLib
    return "${env.WORKSPACE}/BASH_LIBRARY.lib"
}


/************************************************************************************************************
 * Get working directory for each variants
 * @param 
 * @return Map of
**/
Map getWorkingDir (Map configMap) {
    Map wsMap = [:]
    configMap.variants.each{ v ->
        wsMap[v] = (configMap.rootDir?:configMap.general.rootDir) + '/' + 
                    [getJobBase(), configMap.git?.branch?:
                                    (configMap[v]?.repo?.branch?:
                                        (configMap.repo?.branch?:
                                            (configMap.general?.repo?.branch?:
                                                configMap.scm.branch))), v, env.BUILD_NUMBER].join('_').replaceAll("[\\|,|;|'|(|@|/]",'_')
    }
    return wsMap
}

/************************************************************************************************************
 * Get baseline subvolume directory for each variants
 * @param 
 * @return Map of
**/
Map getSubvolumeBaselineDir(Map configMap) {
    Map subvolumeBaselineMap = [:]
    configMap.variants.each{ v ->
        String baselineSubvolume = configMap[v]?.baselineSubvolume?:
                                    (configMap.baselineSubvolume?:
                                        (configMap.general.baselineSubvolume?:'NONBTRFS'))

        subvolumeBaselineMap[v] = baselineSubvolume
        if((!baselineSubvolume.contains('/')) && (baselineSubvolume != 'NONBTRFS')) {
            subvolumeBaselineMap[v] = (configMap.rootDir?:configMap.general.rootDir) + '/' + baselineSubvolume
            if (baselineSubvolume.contains('%s')){
                subvolumeBaselineMap[v] = (configMap.rootDir?:configMap.general.rootDir) + '/' + 
                                            String.format(baselineSubvolume, configMap.git?.branch?:
                                                                                (configMap[v]?.repo?.branch?:
                                                                                    (configMap.repo?.branch?:
                                                                                        (configMap.general?.repo?.branch?:
                                                                                            configMap.scm.branch)))+'_'+v).replaceAll("[\\|,|;|'|(|@|/]",'_')
            }
        }
    }
    return subvolumeBaselineMap
}

/************************************************************************************************************
 * Get the least loaded node in supported nodes for job and return a Map
 * @param String runNode
 * @param List variants
 * @return Map of 
**/
Map getBestNode(String runNode, List variants) {
    Map returnNodeMap = [:]
    List nodeList = getNodeList(runNode)
    if (nodeList.size() == 0) {
        new jobUtils().throwPipelineException("All Nodes/Bots are OFFLINE or RESERVED!")
    } else {
        variants.each { var ->
            def bestNode = null
            def availableExecutors = 0
            nodeList.each{ it ->
                hudson.model.Computer node_c = Jenkins.get().getComputer(it)
                def countIdle = node_c.countExecutors() - node_c.countBusy() - returnNodeMap.values().count(it)
                if (bestNode  == null) {
                    bestNode = it
                    availableExecutors = countIdle
                } else {
                    if (countIdle > availableExecutors){
                        bestNode = it
                        availableExecutors = countIdle
                    }
                }
            }
            returnNodeMap[var] = bestNode
        }
    }
    return returnNodeMap
}

/************************************************************************************************************
 * Get the least loaded node in supported nodes for job and return a Map
 * @param 
 * @return Map of 
**/
Map getBestNode(Map runNodeMap, List variants) {
    Map returnNodeMap = [:]
    if (runNodeMap.keySet().unique().size() < variants.size()){
        new jobUtils().throwPipelineException("Some Variants Don't Have Assigned Nodes In Config!")
    } else {
        variants.each { var ->
            List nodeList = getNodeList(runNodeMap[var])
            if (nodeList.size() == 0) {
                new jobUtils().throwPipelineException("All ${var} Nodes/Bots Are OFFLINE Or RESERVED!")
            } else {
                def bestNode = null
                def availableExecutors = 0
                nodeList.each{ it ->
                    hudson.model.Computer node_c = Jenkins.get().getComputer(it)
                    def countIdle = node_c.countExecutors() - node_c.countBusy() - returnNodeMap.values().count(it)
                    if (bestNode  == null) {
                        bestNode = it
                        availableExecutors = countIdle
                    } else {
                        if (countIdle > availableExecutors){
                            bestNode = it
                            availableExecutors = countIdle
                        }
                    }
                }
                returnNodeMap[var] = bestNode
            }
        }
    }
    return returnNodeMap
}

/************************************************************************************************************
 * Set up some global variables which will be used in configMap
 * @param script the context of caller class
 * @param configMap current configMap
 * @return void
**/
def setGlobalConfigMap(script, configMap) {
    /*BEGINNING OF Serve The Back Up Strategy For Rerunning The Build*/
    script.configMap.env = configMap.env?:[:]
    
    /*User Rerun For Verify Commit, So Set Gerrit Event Variable Here, For Verify Type Run*/
    /*User Rerun For Verify Commit, So Set Gerrit Event Variable Here, For Verify Type Run*/
    script.configMap.isGetFlash = false
    script.configMap.isChangeTriggered = configMap.isChangeTriggered?:(new gerritUtils().isChangeTriggered())
    if (script.configMap.isChangeTriggered) {
        script.configMap.verifyScore = 1
        script.configMap.env.GERRIT_EVENT_TYPE = script.env.GERRIT_EVENT_TYPE?:configMap.env.GERRIT_EVENT_TYPE
        script.configMap.env.GERRIT_EVENT_HASH = script.env.GERRIT_EVENT_HASH?:configMap.env.GERRIT_EVENT_HASH
        script.configMap.env.GERRIT_CHANGE_WIP_STATE = script.env.GERRIT_CHANGE_WIP_STATE?:configMap.env.GERRIT_CHANGE_WIP_STATE
        script.configMap.env.GERRIT_CHANGE_PRIVATE_STATE = script.env.GERRIT_CHANGE_PRIVATE_STATE?:configMap.env.GERRIT_CHANGE_PRIVATE_STATE
        script.configMap.env.GERRIT_BRANCH = script.env.GERRIT_BRANCH?:configMap.env.GERRIT_BRANCH
        script.configMap.env.GERRIT_TOPIC = script.env.GERRIT_TOPIC?:configMap.env.GERRIT_TOPIC
        script.configMap.env.GERRIT_CHANGE_NUMBER = script.env.GERRIT_CHANGE_NUMBER?:configMap.env.GERRIT_CHANGE_NUMBER
        script.configMap.env.GERRIT_CHANGE_ID = script.env.GERRIT_CHANGE_ID?:configMap.env.GERRIT_CHANGE_ID
        script.configMap.env.GERRIT_PATCHSET_NUMBER = script.env.GERRIT_PATCHSET_NUMBER?:configMap.env.GERRIT_PATCHSET_NUMBER
        script.configMap.env.GERRIT_PATCHSET_REVISION = script.env.GERRIT_PATCHSET_REVISION?:configMap.env.GERRIT_PATCHSET_REVISION
        script.configMap.env.GERRIT_REFSPEC = script.env.GERRIT_REFSPEC?:configMap.env.GERRIT_REFSPEC
        script.configMap.env.GERRIT_PROJECT = script.env.GERRIT_PROJECT?:configMap.env.GERRIT_PROJECT
        script.configMap.env.GERRIT_CHANGE_SUBJECT = script.env.GERRIT_CHANGE_SUBJECT?:configMap.env.GERRIT_CHANGE_SUBJECT
        script.configMap.env.GERRIT_CHANGE_COMMIT_MESSAGE = script.env.GERRIT_CHANGE_COMMIT_MESSAGE?:configMap.env.GERRIT_CHANGE_COMMIT_MESSAGE
        script.configMap.env.GERRIT_CHANGE_URL = script.env.GERRIT_CHANGE_URL?:configMap.env.GERRIT_CHANGE_URL
        script.configMap.env.GERRIT_CHANGE_OWNER = script.env.GERRIT_CHANGE_OWNER?:configMap.env.GERRIT_CHANGE_OWNER
        script.configMap.env.GERRIT_CHANGE_OWNER_NAME = script.env.GERRIT_CHANGE_OWNER_NAME?:configMap.env.GERRIT_CHANGE_OWNER_NAME
        script.configMap.env.GERRIT_CHANGE_OWNER_EMAIL = script.env.GERRIT_CHANGE_OWNER_EMAIL?:configMap.env.GERRIT_CHANGE_OWNER_EMAIL
        script.configMap.env.GERRIT_PATCHSET_UPLOADER = script.env.GERRIT_PATCHSET_UPLOADER?:configMap.env.GERRIT_PATCHSET_UPLOADER
        script.configMap.env.GERRIT_PATCHSET_UPLOADER_NAME = script.env.GERRIT_PATCHSET_UPLOADER_NAME?:configMap.env.GERRIT_PATCHSET_UPLOADER_NAME
        script.configMap.env.GERRIT_PATCHSET_UPLOADER_EMAIL = script.env.GERRIT_PATCHSET_UPLOADER_EMAIL?:configMap.env.GERRIT_PATCHSET_UPLOADER_EMAIL
        script.configMap.env.GERRIT_EVENT_ACCOUNT = script.env.GERRIT_EVENT_ACCOUNT?:configMap.env.GERRIT_EVENT_ACCOUNT
        script.configMap.env.GERRIT_EVENT_ACCOUNT_NAME = script.env.GERRIT_EVENT_ACCOUNT_NAME?:configMap.env.GERRIT_EVENT_ACCOUNT_NAME
        script.configMap.env.GERRIT_EVENT_ACCOUNT_EMAIL = script.env.GERRIT_EVENT_ACCOUNT_EMAIL?:configMap.env.GERRIT_EVENT_ACCOUNT_EMAIL
        script.configMap.env.GERRIT_NAME = script.env.GERRIT_NAME?:configMap.env.GERRIT_NAME
        script.configMap.env.GERRIT_HOST = script.env.GERRIT_HOST?:configMap.env.GERRIT_HOST
        script.configMap.env.GERRIT_PORT = script.env.GERRIT_PORT?:configMap.env.GERRIT_PORT
        script.configMap.env.GERRIT_SCHEME = script.env.GERRIT_SCHEME?:configMap.env.GERRIT_SCHEME
        script.configMap.env.GERRIT_VERSION = script.env.GERRIT_VERSION?:configMap.env.GERRIT_VERSION
        script.configMap.env.GERRIT_EVENT_COMMENT_TEXT = script.env.GERRIT_EVENT_COMMENT_TEXT?:(configMap.env.GERRIT_EVENT_COMMENT_TEXT?:'dmVyaWZ5Cg==')
        if(new jobUtils().base64Decode(script.configMap.env.GERRIT_EVENT_COMMENT_TEXT).trim().contains('__get_flash')) {
            script.configMap.isGetFlash = true
            new gerritUtils().gerritPostComment(
                    script.configMap.env.GERRIT_CHANGE_NUMBER,
                        script.configMap.env.GERRIT_PATCHSET_NUMBER,
                            new jobUtils().base64Decode(script.configMap.env.GERRIT_EVENT_COMMENT_TEXT).trim().toUpperCase().replaceAll('__','').replaceAll('_','-') + " Pipeline Has Started: \n\t\t${script.env.BUILD_URL}consoleFull")
        } else {
            new gerritUtils().gerritSetReview(
                    script.configMap.env.GERRIT_CHANGE_NUMBER,
                        script.configMap.env.GERRIT_PATCHSET_NUMBER,
                            new jobUtils().base64Decode(script.configMap.env.GERRIT_EVENT_COMMENT_TEXT).trim().toUpperCase().replaceAll('__','').replaceAll('_','-') + " Pipeline Has Started: \n\t\t${script.env.BUILD_URL}consoleFull", 0)
        }
        if(new jobUtils().base64Decode(script.configMap.env.GERRIT_EVENT_COMMENT_TEXT).trim().contains('_clean__')){
            script.configMap.isCleanBuild = true
        }
    }

    /*User Rerun For Merge Commit, So Set Gerrit Event Variable Here, For Merge Commit Type Run*/
    script.configMap.isMergeTriggered = configMap.isMergeTriggered?:(new gerritUtils().isMergeTriggered())
    if (script.configMap.isMergeTriggered) {
        script.configMap.env.GERRIT_EVENT_TYPE = script.env.GERRIT_EVENT_TYPE?:configMap.env.GERRIT_EVENT_TYPE
        script.configMap.env.GERRIT_EVENT_HASH = script.env.GERRIT_EVENT_HASH?:configMap.env.GERRIT_EVENT_HASH
        script.configMap.env.GERRIT_REFNAME = script.env.GERRIT_REFNAME?:configMap.env.GERRIT_REFNAME
        script.configMap.env.GERRIT_PROJECT = script.env.GERRIT_PROJECT?:configMap.env.GERRIT_PROJECT
        script.configMap.env.GERRIT_OLDREV = script.env.GERRIT_OLDREV?:configMap.env.GERRIT_OLDREV
        script.configMap.env.GERRIT_NEWREV = script.env.GERRIT_NEWREV?:configMap.env.GERRIT_NEWREV
        script.configMap.env.GERRIT_EVENT_ACCOUNT = script.env.GERRIT_OLDREV?:configMap.env.GERRIT_OLDREV
        script.configMap.env.GERRIT_EVENT_ACCOUNT_NAME = script.env.GERRIT_EVENT_ACCOUNT_NAME?:configMap.env.GERRIT_EVENT_ACCOUNT_NAME
        script.configMap.env.GERRIT_EVENT_ACCOUNT_EMAIL = script.env.GERRIT_EVENT_ACCOUNT_EMAIL?:configMap.env.GERRIT_EVENT_ACCOUNT_EMAIL
        script.configMap.env.GERRIT_NAME = script.env.GERRIT_NAME?:configMap.env.GERRIT_NAME
        script.configMap.env.GERRIT_HOST = script.env.GERRIT_HOST?:configMap.env.GERRIT_HOST
        script.configMap.env.GERRIT_PORT = script.env.GERRIT_PORT?:configMap.env.GERRIT_PORT
        script.configMap.env.GERRIT_SCHEME = script.env.GERRIT_SCHEME?:configMap.env.GERRIT_SCHEME
        script.configMap.env.GERRIT_VERSION = script.env.GERRIT_VERSION?:configMap.env.GERRIT_PORT
    }

    /*User Rerun For Timer Base, For Snapshot Type Run*/
    script.configMap.isTimerTriggered = configMap.isTimerTriggered?:(new gerritUtils().isTimerTriggered())
    if (script.configMap.isTimerTriggered) {
        
    }
}

/************************************************************************************************************
 * Set up job configuration base on input from configMap
 * @param script the context of caller class
 * @param configMap current configMap
 * @return void
**/
def setJenkinsJobConfig(script, configMap){
    /*Backing up params for reference only*/
    script.configMap.params = [:]
    script.configMap.params.CAUSED_BY = script.params.CAUSED_BY
    script.configMap.params.RUN_ON_VERSION = script.params.RUN_ON_VERSION
    script.configMap.params.RUN_FOR_VARIANTS = script.params.RUN_FOR_VARIANTS
    script.configMap.params.RERUN_FROM_STAGE = script.params.RERUN_FROM_STAGE
    script.configMap.params.STAGES_TO_SKIP = script.params.STAGES_TO_SKIP
    script.configMap.params.FORCE_RUN = script.params.FORCE_RUN
    script.configMap.params.CLEAN_BUILD = script.params.CLEAN_BUILD

    if (configMap.jobConfig?.trigger?.gerrit?.triggerEvent?.equalsIgnoreCase('verify')){
        /*Read More Here: https://www.jenkins.io/doc/pipeline/steps/params/pipelinetriggers*/
        script.configMap.isCleanBuild = false
        script.configMap.pipelineType = 'verify'
        script.properties([
            pipelineTriggers([
                [
                    $class: 'GerritTrigger',
                    gerritProjects: [
                        [
                            $class: "GerritProject",
                            compareType: "REG_EXP",
                            pattern: configMap.jobConfig?.trigger?.gerrit?.project?.name?:configMap.scm.url.tokenize(':')[-1].replaceAll(configMap.scm.port+'/',''),
                            branches: [
                                [
                                    $class: "Branch",
                                    compareType: "REG_EXP",
                                    pattern: configMap.jobConfig?.trigger?.gerrit?.project?.branch?:configMap.scm.branch.toString()
                                ]
                            ]
                        ]
                    ],
                    buildAbortedMessage: "[VERIFICATION ABORTED] \n\t\t${script.env.BUILD_URL}consoleFull",
                    buildStartMessage: "[VERIFICATION STARTED] \n\t\t${script.env.BUILD_URL}consoleFull",
                    gerritBuildAbortedVerifiedValue: 0,
                    gerritBuildFailedVerifiedValue: -1,
                    gerritBuildStartedVerifiedValue: 0,
                    gerritBuildSuccessfulVerifiedValue: 1,
                    silentMode: configMap.jobConfig?.trigger?.gerrit?.silentMode?:false,
                    skipVote : [
                        onNotBuilt: true,
                        onAborted: true
                    ],
                    triggerOnEvents: [
                        [
                            $class: "PluginPatchsetCreatedEvent", 
                            excludeDrafts: true,
                            excludeNoCodeChange:false,
                            excludePrivateState:true,
                            excludeTrivialRebase: false,
                            excludeWipState: true
                        ],
                        [ $class: "PluginDraftPublishedEvent" ],
                        [ $class: "PluginChangeRestoredEvent" ],
                        //[ $class: "PluginTopicChangedEvent" ],
                        [
                            $class: "PluginCommentAddedContainsEvent",
                            commentAddedCommentContains: configMap.jobConfig?.trigger?.gerrit?.commentAddedCommentContains?:''
                        ]
                    ],
                    dynamicTriggerConfiguration: configMap.jobConfig?.trigger?.gerrit?.dynamicTriggerConfiguration?:false,
                    triggerConfigURL: configMap.jobConfig?.trigger?.gerrit?.triggerConfigURL?:''
                ]
            ])
        ])
    } else if (configMap.jobConfig?.trigger?.gerrit?.triggerEvent?.equalsIgnoreCase('submit')) {
        script.configMap.isCleanBuild = false
        script.configMap.pipelineType = 'devel'
        script.properties([
            disableConcurrentBuilds(),
            pipelineTriggers([
                [
                    $class: 'GerritTrigger',
                    gerritProjects: [
                        [
                            $class: "GerritProject",
                            compareType: "REG_EXP",
                            pattern: configMap.jobConfig?.trigger?.gerrit?.project?.name?:'',
                            branches: [
                                [
                                    $class: "Branch",
                                    compareType: "REG_EXP",
                                    pattern: configMap.jobConfig?.trigger?.gerrit?.project?.branch?:''
                                ]
                            ]
                        ]
                    ],
                    silentMode: configMap.jobConfig?.trigger?.gerrit?.silentMode?:false,
                    skipVote : [
                        onNotBuilt: true,
                        onAborted: true
                    ],
                    triggerOnEvents: [
                        [ $class: "PluginRefUpdatedEvent" ]
                    ],
                    dynamicTriggerConfiguration: configMap.jobConfig?.trigger?.gerrit?.dynamicTriggerConfiguration?:false,
                    triggerConfigURL: configMap.jobConfig?.trigger?.gerrit?.triggerConfigURL?:''
                ]
            ])
        ])
    } else if (configMap.jobConfig?.trigger?.gerrit?.triggerEvent?.equalsIgnoreCase('comment')) {
        script.configMap.isCleanBuild = true
        script.configMap.pipelineType = 'comment'
        script.properties([
            pipelineTriggers([
                [
                    $class: 'GerritTrigger',
                    gerritProjects: [
                        [
                            $class: "GerritProject",
                            compareType: "REG_EXP",
                            pattern: configMap.jobConfig?.trigger?.gerrit?.project?.name?:configMap.scm.url.tokenize(':')[-1].replaceAll(configMap.scm.port+'/',''),
                            branches: [
                                [
                                    $class: "Branch",
                                    compareType: "REG_EXP",
                                    pattern: configMap.jobConfig?.trigger?.gerrit?.project?.branch?:configMap.scm.branch.toString()
                                ]
                            ]
                        ]
                    ],
                    silentMode: configMap.jobConfig?.trigger?.gerrit?.silentMode?:false,
                    skipVote : [
                        onNotBuilt: true,
                        onAborted: true
                    ],
                    triggerOnEvents: [
                        [
                            $class: "PluginCommentAddedContainsEvent",
                            commentAddedCommentContains: configMap.jobConfig?.trigger?.gerrit?.commentAddedCommentContains?:''
                        ]
                    ],
                    dynamicTriggerConfiguration: configMap.jobConfig?.trigger?.gerrit?.dynamicTriggerConfiguration?:false,
                    triggerConfigURL: configMap.jobConfig?.trigger?.gerrit?.triggerConfigURL?:''
                ]
            ])
        ])
    }  else {
        script.configMap.isCleanBuild = true
        script.configMap.pipelineType = 'nightly'
        if (configMap.jobConfig?.disableConcurrentBuilds){
            script.properties([
                disableConcurrentBuilds()
            ])
        }
    }
}

/************************************************************************************************************
 * This method collects a list of Node names from the current Jenkins instance
 **/
private List getNodeList(String runNode) {
    List nodes = []
    List finalNodes = []
    // runNode can be list of nodes or list of labels
    runNode.replaceAll("[,;]",' ').tokenize(' ').each { bot ->
        if (Jenkins.get().getNode(bot) == null) {
            // bot is label -> get all the nodes with assigned label
            nodes +=  jenkins.model.Jenkins.get().computers.findAll{ it.node.labelString.contains(bot) }.collect{ it.node.selfLabel.name }
        } else {
            // bot is actual node name
            nodes.add(bot)
        }
    }
    return nodes.unique().collect{ isNodeUsable(it)?it:null}.findAll {it}
}

/************************************************************************************************************
 * Check if node can be used
 **/
private Boolean isNodeUsable(String runNode) {
    if (! Jenkins.get().getNode(runNode).toComputer().isOnline()) {
      echo "WARNING: Node " + runNode + " is offline. Can't use it."
      return false
    }
    if (Jenkins.get().getNode(runNode).labelString.toUpperCase().contains('RESERVED')) {
      echo "WARNING: Can't use RESERVED node " + runNode + ". Remove RESERVED label from manage nodes page if you want."
      return false
    }
    return true
}

/************************************************************************************************************
 * Get job base name which is different in case of multi branch job
 * @param 
 * @return String
**/
private String getJobBase() {
    def project = currentBuild.rawBuild.project
    return (project.parent instanceof org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject)?
            project.parent.displayName.toString() : project.displayName.toString()
}

private Map loadConfigurationFromFile(String configFile='eb/global/config.yml') {
    Map configMap = [:]
    try {
        String ymlConfigString = libraryResource configFile
        configMap = isYaml(configFile) ? readYaml(text: ymlConfigString) : [:]
    } catch (err) {
        new jobUtils().throwPipelineException("${configFile}: ${err.toString()}".toString())
    }
    return configMap
}

private boolean isYaml(String fileName) {
    return fileName.endsWith(".yml") || fileName.endsWith(".yaml")
}

private getScm(nodeLabel) {
    if (scm instanceof hudson.plugins.git.GitSCM) {
        def credentialsId = scm.userRemoteConfigs.find{true}.credentialsId.toString()
        def _scm = node(nodeLabel){checkout scm}
        [
            branch: _scm.GIT_BRANCH.replaceAll('origin/',''),
            url: _scm.GIT_URL,
            commit: _scm.GIT_COMMIT,
            creds: credentialsId,
            host: _scm.GIT_URL.tokenize(':')[1].replaceAll('^//','').tokenize('@')[-1],
            port: _scm.GIT_URL.tokenize(':')[-1].tokenize('/')[0].isNumber()?_scm.GIT_URL.tokenize(':')[-1].tokenize('/')[0]:'29418'
        ]
    } else {
        new jobUtils().throwPipelineException("Missing SCM Repo Entry In Job Configuration!")
    }
}

private loadProjectLibrary(_scm) {
    if (scm instanceof hudson.plugins.git.GitSCM) {
        library identifier: 'pipeline-project-library@' + _scm.commit,
        retriever: modernSCM(
        [
          $class: 'GitSCMSource',
            remote: _scm.url,
            credentialsId: _scm.creds,
            extensions: [
            [
              $class: 'SubmoduleOption', 
              disableSubmodules: false, 
              parentCredentials: true, 
              recursiveSubmodules: true, 
              trackingSubmodules: false
            ]]
        ])
    } else {
        library 'pipeline-project-library'
    }
}