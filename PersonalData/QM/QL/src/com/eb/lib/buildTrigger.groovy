package com.eb.lib
import com.eb.lib.jobUtils
import com.eb.lib.commonEnvironment
import hudson.model.queue.ScheduleResult
import hudson.console.ModelHyperlinkNote
import jenkins.model.ParameterizedJobMixIn

/************************************************************************************************************
 * To Replace existing build() from jenkins with better flow handlding and more features
 * 
 * @param job: full job name without /job/
 * @param wait: true/false (default will be true)
 * @param propagate: true/false (default will be true. when wait is false, propagate will be disregarded)
 * @param timeOut: time out for downstream job in second (default is 7200 seconds)
 * @param quietPeriod: idle time before triggering downstream job (default is random number)
 * @param credential: credential ID from jenkins to run trigger build
 * @param parameters: list of build params
 * Good practice to use parameter classes like: StringParameterValue, BooleanParameterValue, etc
 * How to use example: 
 *  import com.eb.lib.buildTrigger
 *  new buildTrigger().build(job: "playground/downstream", wait: false, propagate: false, timeOut: 1800, quietPeriod: 2,
 *                      parameters: [
 *                          new StringParameterValue('CAUSED_BY', env.BUILD_URL),
 *                          new BooleanParameterValue('DISABLE_TESTING', true),
 *                          'CAUSED_BY='+env.BUILD_URL, // this will be wrapped into StringParameterValue class
 *                          'DISABLE_TESTING='+'true' // this will be wrapped into BooleanParameterValue class
 *                       ]) 
 * 
 * @return true/false
 * 
 * Known Limitation:
 * Jenkins hasn't provide method to get Executable URL from QueueId yet, so we have to make it through REST API
**/
Boolean build(Map args) {
    String job =  args.job
    List parameters = args.parameters
    Integer timeOut = (args.timeOut?:7200).toInteger() //default 2 hours
    Integer quietPeriod = (args.quietPeriod?:-1).toInteger()
    Boolean wait = ((args.wait!=null)?args.wait:true).toBoolean()
    Boolean propagate = ((args.propagate!=null)?args.propagate:true).toBoolean()

    String queueID = getQueueID(job, wait, quietPeriod, parameters)
    if (wait) {
        String queueUrl = "${JENKINS_URL}queue/item/${queueID}/api/json"
        String executableURL = ''
        String buildResult = ''
        sleep 10
        while(!executableURL){
            Map restResponse = new jobUtils().parseJsonToMap(httpRequest(queueUrl))
            if(restResponse.blocked){
                Thread.sleep(4*1000)
            } else if(restResponse.executable?.url){
                executableURL = restResponse.executable.url
                break
            } else {
                Thread.sleep(2*1000)
            }
        }
        println("Starting: " + hudson.console.ModelHyperlinkNote.encodeTo("${executableURL}console", job.replace("/"," » ") + " #" + executableURL.tokenize('/')[-1]))
        buildResult = getBuildResult(job, executableURL.tokenize('/')[-1], timeOut)
        println("[${buildResult}]: " + hudson.console.ModelHyperlinkNote.encodeTo("${executableURL}console", job.replace("/"," » ") + " #" + executableURL.tokenize('/')[-1]))
        if (propagate){
            currentBuild.result = buildResult.equals("SUCCESS")?"SUCCESS":"FAILURE"
            if (buildResult != "SUCCESS") {
                error("${buildResult} In Downstream Job » ${executableURL}console")
            }
        }
    }

    return currentBuild.result.equals("SUCCESS")?true:false
}

/************************************************************************************************************
 * http request with basic authentication. The credential will be used from jenkins's credentials
 *
 * @param requestURL url to have http request
 * @param retryUntilSuccess with maximum of 10 retries.
 *
 * @return Map parsed json output to Map
**/
private String httpRequest(String requestURL) {
    def responseJson
    try {
        responseJson = httpRequest consoleLogResponseBody: false,
                                quiet: true,
                                url: requestURL.trim(),                       
                                customHeaders:[[
                                    name:'Authorization', 
                                    value:"Basic " + "${env.DEFAULT_CREDENTIAL_USR}:${env.DEFAULT_CREDENTIAL_PSW}".getBytes().encodeBase64().toString()]]
    } catch(err){
        println(err)
        new jobUtils().throwRuntimeException("[ERROR] REST Request For ${requestURL} - " + err.getMessage())
    }
    return responseJson.getContent()
}

/************************************************************************************************************
 * Get build result by giving job name, build id number and timeOut to query build status.
 *
 * @param job full name of the job
 * @param buildNumber the build id of the job to query status
 * @param timeOut maximum excepted time to query the build status.
 *
 * @return Map parsed json output to Map
**/
private getBuildResult (job, buildNumber, timeOut) {
    long startTime = System.currentTimeMillis()/1000
    String buildResult = null
    def runItem = Jenkins.instance.getItemByFullName(job).getBuild(buildNumber)
    while((System.currentTimeMillis()/1000 - startTime) < timeOut) {
        if(!runItem.isBuilding()) {
            buildResult = runItem.getResult()
            if(buildResult){
                break
            }
        } else {
            Thread.sleep(2*1000)
        }
    }
    return buildResult?:"TIMEOUT"
}

/************************************************************************************************************
 * Trigger downstream job and return queueID
 * 
 * @param job: full job name without /job/
 * @param wait: true/false (default will be true)
 * @param quietPeriod: idle time before triggering downstream job (default is random number)
 * @param parameters: list of build params
 * 
 * @return queueID
**/
private String getQueueID (job, wait, quietPeriod, parameters){
    Queue.Item queueItem
    Item item = Jenkins.instance.getItemByFullName(job, Item.class)
    if (item == null) {
        new jobUtils().throwPipelineException("No Job Named: " + job + " Found In Jenkins!")
    }
    item.checkPermission(Item.BUILD)
    if (wait && !(item instanceof Job)) {
        new jobUtils().throwPipelineException("Waiting For Non-Job Item: " + job + " Is Not Supported")
    }

    List actions = []

    if (item instanceof ParameterizedJobMixIn.ParameterizedJob) {
        ParameterizedJobMixIn.ParameterizedJob dtJob = (ParameterizedJobMixIn.ParameterizedJob) item
        if (parameters != null) {
            parameters = completeParameters(parameters, (Job) dtJob)
            actions.add(new ParametersAction(parameters))
        }
        println("Scheduling Job: " + ModelHyperlinkNote.encodeTo(dtJob))

        queueItem = ParameterizedJobMixIn.scheduleBuild2((Job) dtJob, quietPeriod, actions.toArray(new Action[0]))
        if (queueItem == null || queueItem.getFuture() == null) {
            new jobUtils().throwPipelineException("Failed To Trigger Build Of " + dtJob.getFullName())
        }
    } else if (item instanceof Queue.Task) {
        if (parameters) {
            new jobUtils().throwPipelineException("Item Type Does Not Support Parameters!")
        }
        Queue.Task task = (Queue.Task) item
        println("Scheduling Item: " + ModelHyperlinkNote.encodeTo(item))

        ScheduleResult scheduleResult = Jenkins.get().getQueue().schedule2(task, quietPeriod, actions)
        if (scheduleResult.isRefused()) {
            new jobUtils().throwPipelineException("Failed to trigger build of " + item.getFullName())
        } else {
            queueItem = scheduleResult.getItem()
        }
    } else {
        new jobUtils().throwPipelineException("The Item Named " + job + " Is A "
                + (item instanceof Describable
                ? ((Describable) item).getDescriptor().getDisplayName()
                : item.getClass().getName())
                + " Which Is Not Something That Can Be Built")
    }
    return queueItem.getId().toString()
}

/************************************************************************************************************
 * Get default parameters and values in downstream job, in case, upstream misses from calling
 * 
 * @param parameters input parameters from upstream job
 * @param dtJob downstream job Item.class (or Job.class)
 * 
 * @return new list of parameters
**/

private List completeParameters(List parameters, Job dtJob) {
    Map allParameters = [:]
    try{
        parameters.each { pv->
            // In case pure string as parameter, classify it with StringParameterValue
            if(pv instanceof String){
                def pvKey = pv.tokenize('=')[0].trim()
                def pvValue = pv.tokenize('=')[1].trim()
                if (pvValue.contains('true') || pvValue.contains('false')){
                    pv = new BooleanParameterValue(pvKey, pvValue.toBoolean())
                } else {
                    pv = new StringParameterValue(pvKey, pvValue)
                }
            }
            allParameters.put(pv.getName(), pv)
        }
        if (dtJob != null) {
            ParametersDefinitionProperty pdp = dtJob.getProperty(ParametersDefinitionProperty.class)
            if (pdp != null) {
                for (ParameterDefinition pDef : pdp.getParameterDefinitions()) {
                    if (!allParameters.containsKey(pDef.getName())) {
                        // Append parameter list if downstream defaul parameters missed from upstream call
                        ParameterValue defaultP = pDef.getDefaultParameterValue()
                        if (defaultP != null) {
                            allParameters.put(defaultP.getName(), defaultP)
                        }
                    } else {
                        // Evaluate parameters in ChoiceParameterDefinition  
                        if (pDef instanceof ChoiceParameterDefinition) {
                            ParameterValue pv = allParameters.get(pDef.getName())
                            if (!((ChoiceParameterDefinition)pDef).getChoices().contains(pv.getValue())) {
                                new jobUtils().throwPipelineException("Value for choice parameter '" + pDef.getName() + "' is '" + pv.getValue() + "', "
                                        + "but valid choices are " + ((ChoiceParameterDefinition)pDef).getChoices())
                            }
                        } else if (pDef instanceof SimpleParameterDefinition && !(pDef instanceof StringParameterDefinition)) {
                            ParameterValue pv = allParameters.get(pDef.getName())
                            if (pv instanceof StringParameterValue) {
                                String pDefDisplayName = pDef.getDescriptor().getDisplayName()
                                ParameterValue convertedValue = ((SimpleParameterDefinition) pDef).createValue((String) pv.getValue())
                                allParameters.put(pDef.getName(), convertedValue)
                            }
                        }
                    }
                }
            }
        }
        return allParameters.values()
    } catch(err) {
        new jobUtils().throwPipelineException("Parameter List To Trigger Downstream Job Is Buggy!")
    }
}