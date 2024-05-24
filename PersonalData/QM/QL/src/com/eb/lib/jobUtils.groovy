package com.eb.lib
import com.eb.lib.commonEnvironment
import com.eb.lib.PipelineEnvironmentException
import groovy.json.JsonSlurperClassic
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.codehaus.groovy.runtime.StackTraceUtils
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import io.jenkins.blueocean.rest.impl.pipeline.PipelineNodeGraphVisitor
/************************************************************************************************************
 * Strong strip to remove all special characters except alphabet and digits
 * @param string to strip
 * 
 * @return String output
**/
String stripSpecialChars(String string) {
    return string.replaceAll("[^a-zA-Z0-9 ]+","")
}

/************************************************************************************************************
 * Getting random string from a->z, between 5->25 characters long.
 * @param 
 * @return String
**/
String randomString() {
    def stringList = ('a'..'z').collect { it }
    Collections.shuffle(stringList)
    def randomNumList = (5..25).collect { it }
    Collections.shuffle(randomNumList)
    def randomNum = randomNumList.take(1).join().toInteger()
    return stringList.take(randomNum).join()
}


/************************************************************************************************************
 * http request with basic authentication. The credential will be used from jenkins's credentials
 *
 * @param requestURL url to have http request
 * @param retryUntilSuccess with maximum of 10 retries.
 *
 * @return Map parsed json output to Map
**/
String httpRequest(String requestURL) {
    def responseJson
    try {
        responseJson = httpRequest consoleLogResponseBody: true,
                                quiet: false,
                                url: requestURL.trim(),                       
                                customHeaders:[[
                                    name:'Authorization', 
                                    value:"Basic " + "${env.DEFAULT_CREDENTIAL_USR}:${env.DEFAULT_CREDENTIAL_PSW}".getBytes().encodeBase64().toString()]]
    } catch(err){
        println(err)
        new jobUtils().throwRuntimeException("[ERROR] REST Request For ${requestURL} - " + err.getMessage())
    }
    return responseJson.getContent().replaceAll("\\)]}\'","")
}

/************************************************************************************************************
 * encode Base64 input
 * @param inputString message to printout after encoding base64
 * 
 * @return String output
**/
String base64Encode(inputString){
    return inputString.bytes.encodeBase64().toString()
}

/************************************************************************************************************
 * decode Base64 input to String
 * @param encodedString message to printout after decoding base64
 * 
 * @return String output
**/
String base64Decode(encodedString){
    byte[] decoded = encodedString.decodeBase64()
    return new String(decoded)
}

/************************************************************************************************************
 * Convert map back to yaml.
 * @param Map projectConfig
 * @return yaml string format
**/
@Grab("org.yaml:snakeyaml:*")
String mapToYaml(Map inMap){
    return new org.yaml.snakeyaml.Yaml().dump(inMap).toString()
}

/************************************************************************************************************
 * Merge 2 or more maps together base on key-value
 * @param Map[] maps
 * 
 * @return Map
**/
Map mergeMapRecursive(Map[] maps) {
    Map result

    if (maps.length == 0) {
          result = [:]
    } else if (maps.length == 1) {
          result = maps[0]
    } else {
        result = [:]
        maps.each { map ->
            map.each { k, v ->
                result[k] = result[k] instanceof Map ? mergeMapRecursive(result[k], v) : v
            }
        }
    }
    return result
}

/************************************************************************************************************
 * Pretty Print for debugging
 * @param msg message to printout
**/
def prettyPrint(String msg){
    def max=100
    def a = "#"
    def barrierleng = (max-msg.length())/2-1
    barrierleng = (barrierleng < 0) ? 4 : barrierleng
    def barrier = a*barrierleng
    println(barrier + " " + msg + " " + barrier)
}

/************************************************************************************************************
 * Get Stack Trace, with simulating null pointer exception
 * @param
**/
def getStackTrace() {
    try {
        null.class.toString()
    } catch (NullPointerException e) {
        return e.getStackTrace()
    }
    return null
}


/************************************************************************************************************
 * Print Stack Trace For Debugging
 * @param
**/
def printStackTrace() {
    def stackTraceStr = "STACK TRACE DEBUG:\n"
    def isCallingFunc = false
    for (StackTraceElement st : getStackTrace()) {
        if (isCallingFunc) {
            stackTraceStr += st.toString() + '\n'
            if (st.toString().contains('.call(')) { break }
        }
        if (!isCallingFunc && st.toString().startsWith(this.class.name + '.printStackTrace(')) {
            isCallingFunc = true
        }
    }
    println(stackTraceStr)
}


/************************************************************************************************************
 * Throw Exception with setting build/stage status and messages
 * @param encodedString message to printout after decoding base64
 * 
 * @return String output
**/
def throwPipelineException(String errMsg= '', status='FAILURE', throwError=true) {
    printStackTrace()
    catchError(buildResult: status, stageResult: status) {
        error(errMsg)
    }
    if(throwError) {
        throw new PipelineEnvironmentException(errMsg?errMsg.toString():'') 
    }
}

/************************************************************************************************************
 * Throw Exception with setting build/stage status and messages
 * @param encodedString message to printout after decoding base64
 * 
 * @return String output
**/
def throwRuntimeException(String errMsg='', status='FAILURE', throwError=true) {
    printStackTrace()
    catchError(buildResult: status, stageResult: status) {
        error(errMsg)
    }
    if(throwError) {
        throw new RuntimeException(errMsg?errMsg.toString():'') 
    }
}

/************************************************************************************************************
 * Run shell multiple lines like whole script
 *
 * @param cmd Long formatted shell script to run
 *
 * @return
**/
def scriptRun(String cmd, String namePrefix='tmpScript', Boolean errorException=true) {
    String scriptName = [namePrefix,randomString(),new Date().getTime().toString()].join('_')
    try {
        println("[RUN] ${cmd}")
        def scriptContent = "#!/bin/bash\nset -e\necho LOGGING to ${scriptName}.log\nexec > >(tee ${scriptName}.log);\n" + cmd

        writeFile file: "${scriptName}.sh", text: scriptContent
        sh(script:"""#!/bin/bash
                set -e -x
                chmod a+x ${scriptName}.sh
                bash -e -x ${scriptName}.sh
        """)
    } catch(err) {
        if (errorException) {
            currentBuild.result = (err.toString()==null)?'ABORTED':'FAILURE'
            throwRuntimeException("[ERROR] ${scriptName}.sh ---> " + err.toString(), currentBuild.result)
        } else {
            println("[INFO] ${scriptName}.sh ---> ${err.toString()}")
            printStackTrace()
        }
    }
}

/************************************************************************************************************
 * Run shell commands or whole script
 *
 * @param inCmd shell command to run
 *
 * @return stdOutput of all the commands
**/
def runShell(inCmd, errorException=true) {
    String stdOutput = ''
    cmdList = (inCmd instanceof List)?inCmd:[inCmd]
    cmdList.each { cmd ->
        try {
            println("[RUN] ${cmd}")
            stdOutput = stdOutput + "\n" + 
                        sh(returnStdout:true, script:"""#!/bin/bash
set -e
bash <<-EOF
#!/bin/bash
set -e
${cmd}
EOF""").trim()
        } catch(err) {
            if (errorException) {
                currentBuild.result = (err.toString()==null)?'ABORTED':'FAILURE'
                throwRuntimeException("[ERROR] ${cmd} --- " + err.toString(), currentBuild.result)
            } else {
                println("[INFO] ${stdOutput}\n${err.toString()}")
                printStackTrace()
            }
        }
    }
    return stdOutput.trim()
}

/************************************************************************************************************
 * Parse json into Map to access in pipeline
 *
 * @param json is the json input file
 *
 * @return Map object
**/
def parseJsonToMap(String json) {
    try {
        final slurper = new groovy.json.JsonSlurperClassic()
        return new HashMap<>(slurper.parseText(json))
    } catch(err) {
        throwPipelineException(err.toString())
    }

}


Boolean isFilePath(String node, String filePath){
    def fp = new hudson.FilePath(Jenkins.getInstanceOrNull().getComputer(node).getChannel(), filePath.trim())
    if (fp.exists()){
        return true
    } else {
        return false
    }
}

/************************************************************************************************************
 * Return all Jenkins Agents of a label expression
 * - A label expression can be for example "Linux" or "Docker && Linux"
 * - IMPORTANT: Valid expression operators are only (expr) and (expr&&expr), nothing else.
 * - Details: https://jenkins.io/doc/pipeline/steps/workflow-durable-task-step/#code-node-code-allocate-node
 * - we access the jenkins model api, so we have to mark it as NonCPS
 *
 * @param labelexpr specifies label
**/
@NonCPS
def getAllAgentsForALabel(String labelexpr) {
    def result = []
    def requestedLabelExprression = []
    labelexpr.split('&&').each { requestedLabelExprression.add(it.trim()) }
    for (aSlave in hudson.model.Hudson.instance.slaves) {
        if (aSlave.getLabelString()) {
            toBeAdded = true
            for (label in requestedLabelExprression) {
                if (!aSlave.getLabelString().split().contains(label)) {
                    toBeAdded = false
                    break
                }
            }
            if (toBeAdded) {
                result.add(aSlave.name)
            }
        }
    }
    return result
}

/************************************************************************************************************
 * Detects the state of a defined (parallel) stage of the current job
 *
 * @param stageDisplayName: displayName of the stage you would like get the state of
 *
 * @return state of the stage provided as "stageDisplayName" (will be UNDEFINED if stage is not found)
**/
@NonCPS
def getStageState(String stageDisplayName) {
  WorkflowRun run = Jenkins.instance.getItemByFullName(JOB_NAME).getBuild(BUILD_NUMBER)
  PipelineNodeGraphVisitor visitor = new PipelineNodeGraphVisitor(run)
  def stage = visitor.getPipelineNodes().find{ it.displayName ==~ /${stageDisplayName}/ }
  if (stage != null) {
    return stage.getStatus().getState().toString()
  } else {
    return "UNDEFINED"
  }
}


/************************************************************************************************************
 * Clear the Jenkins queue of only the current job. This allows jobs to execute only
 * one build of a latest master branch when there is more than one job queued.
 * Builds queued for other jobs are not touched.
**/
def clearJobQueue() {
  def q = jenkins.model.Jenkins.getInstance().getQueue()
  def items = q.getItems()

  for (item in items) {
    // Strip the current build number from this job's URL to compare against queued build URLs
    rawBaseUrl = currentBuild.rawBuild.getUrl().replaceAll("/${currentBuild.rawBuild.getId()}\\/\$", '/')

    if(item.task.getUrl() == rawBaseUrl) {
      q.cancel(item)
    }
  }
}

/************************************************************************************************************
 *
 * @param jobname: job name without /job/
 * @param buildnumber: build number from jenkins
 * @return state of triggered job
*/
String getArtifactsDir(String jobname, String buildnumber) {
    List artifacturlList =  ["/var/jenkins_home"] + jobname.replace('//','/').tokenize('/')
    String artifacturl =  artifacturlList.join('/jobs/')
    return "${artifacturl}/builds/${buildnumber}/archive"
}

/************************************************************************************************************
 * Check if new build required, comparing 2 manifests
 * @param repoReleaseManifest
 * @return boolean 
*/
Boolean isBuildRequired(String repoReleaseManifest){
    def isNewBuildRequired = runShell("diff -C 2 ${repoReleaseManifest} .repo/manifests/${repoReleaseManifest}")
    return (isNewBuildRequired!='')?true:false
}

def updateSwlVersion(String file, String repo) {
    Date date = new Date()

    // Get Year, weeknumber, weekday, counter details from swl xml file
    def v_content = sh(returnStdout: true, script: "grep \'<VERSION>\' ${file}").trim()
    def (_,vyy,vwn,vwd,vc) = ( v_content =~ /^<VERSION>(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})<\/VERSION>$/)[0]

    // Get today's details using date class
    def w = (date.getAt(Calendar.WEEK_OF_YEAR) - 1 == 0) ? 1 : (date.getAt(Calendar.WEEK_OF_YEAR) - 1)
    def wn = (w < 10) ? "0"+w.toString() : w.toString()
    def dn = "0"+(date.getAt(Calendar.DAY_OF_WEEK) - 1).toString()
    def yr = date.format("yy").toString()

    //Check if there is another build today so that we will increase the counter.
    if ( vyy.toInteger()==yr.toInteger() && vwn.toInteger() == wn.toInteger() && vwd.toInteger() == dn.toInteger() ){
        vc = vc.toInteger()+1
    }else{
        //reset the counter for the firstbuild of the day
        vc=1
    }
    vc = (vc < 10) ? "0"+vc.toString() : vc.toString()
    new_version = yr+'.'+wn+'.'+dn+'.'+vc
    runShell("sed -Eri \"s|<VERSION>(.*)</VERSION>|<VERSION>${new_version}</VERSION>|\" ${file}")
    runShell("git --git-dir=${repo}/.git --work-tree=${repo} commit -a -m 'SWL version change by snapshot' -m 'Tracing-id: T2KB1-13018'")
}

/************************************************************************************************************
 * Set given label to a node
**/
String setNodeLabel(String runNode, String labelString) {
  hudson.model.Node node = Jenkins.get().getNode(runNode)
  def label = node.labelString + " " + labelString
  node.setLabelString(label)
  node.save()
}

/************************************************************************************************************
 * Remove label from a node
**/
String removeNodeLabel(String runNode, String labelString) {
  hudson.model.Node node = Jenkins.get().getNode(runNode)
  def label = node.labelString.replaceAll(labelString, "")
  node.setLabelString(label)
  node.save()
}


/************************************************************************************************************
 * Merges the envList with the contents of the un-stashed properties file
**/
void updateEnvironment(String stageName, String propertiesFile, ArrayList envList) {
    /* is there a way to get the un-stashed files in a String/List? */
    unstash "${stageName}"

    /* parse a pseudo properties_map from a file */
    def properties = readFile("${propertiesFile}").tokenize('\n')

    // Scripts not permitted to use method java.util.List set
    // Scripts not permitted to use DefaultGroovyMethods each | eachWithIndex
    envListClone = []

    /*
     * Update the values in the environment list with those present in the properties map
     */
    for (environment in envList) {
        def environment_key = environment.tokenize('=').getAt(0)
        def environment_value = environment.tokenize('=').getAt(1)

        for (property in properties) {
            def property_key = property.tokenize('=').getAt(0)
            def property_value = property.tokenize('=').getAt(1)

            /* Check for null */
            property_value = property_value ? property_value : ' '

            if (environment_key == property_key && environment_value != property_value){
                echo "INFO: overwriting property ${property_key}"
                echo "'${environment_value}' --> '${property_value}'"
                environment_value = property_value
            // Scripts not permitted to use method java.util.Map remove
            //  properties_map.remove(element_key)
            }
        } /* for properties_map */
        envListClone.add("${environment_key}=${environment_value}")
    } /* for envList */

    /*
     * Now that all values in the environment_list were updated against the properties stored in the stash
     * we shall continue with the new values present on the properties list but not on the environment
     */
    for (property in properties) {
        def exists = false
        def property_key = property.tokenize('=').getAt(0)
        def property_value = property.tokenize('=').getAt(1)

        /* Check for null */
        property_value = property_value ? property_value : ' '

        for (environment in envList) {
            def environment_key = environment.tokenize('=').getAt(0)

            /* mark tag as already present */
            if (property_key == environment_key) {
                /* TODO: add a break */
                exists = true
            }
        } /* for envList */

        if (!exists) {
            echo "INFO: adding new environment value ${property_key}"
            envListClone.add("${property_key}=${property_value}")
        } /* if not updated  */
    } /* for properties_map */

    echo envListClone.toString()
    return envListClone
}

