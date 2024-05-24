import com.eb.lib.jobUtils
import com.eb.lib.buildTrigger

def call(Map parameters) {
    def jobUtils = new jobUtils()
    def script = parameters.script
    def stage = parameters.stage
    def variant = parameters.variant
    def configMap = script.configMap
    def stageConfig = configMap.stageConfig
    def variantConfig = configMap.variantConfig[variant]
/***************************** End of common part *****************************/
    def dtMap = [:]
    def dtJobConfig = variantConfig.dtJobs?.dtJobConfig?:
                        (variantConfig.dtJobConfig?:
                            (stageConfig.dtJobConfig?:[:]))
    def dtJobs = variantConfig.dtJobs?.parallel?:
                    (variantConfig.dtJobs?.sequential?:
                        (variantConfig.dtJobs?:[:]))
    dir(variantConfig.workingDir) {
        docker.image(variantConfig.dockerImage).inside(variantConfig.dockerArgs) {
            if(variantConfig.runScript) {
                jobUtils.scriptRun(variantConfig.runScript, variantConfig.scriptNamePrefix)
            }
            dtJobs.each { kT, vT ->
                def triggerJobConfig = jobUtils.mergeMapRecursive(dtJobConfig, vT.dtJobConfig?:[:])
                def params = ((triggerJobConfig.params instanceof String)?
                                triggerJobConfig.params.replaceAll(",|;"," ").tokenize(' '):
                                    triggerJobConfig.params).collect { 
                                        it.replaceAll("!buildVersion!", variantConfig.buildVersion)
                                            .replaceAll("!workingDir!", variantConfig.workingDir)
                                            .replaceAll("!runNode!", variantConfig.runNode)
                                            .replaceAll("!variant!", variant)
                                            .replaceAll("!pipelineType!", configMap.pipelineType)
                                            .replaceAll("!isCleanBuild!", configMap.isCleanBuild.toString())
                                            .replaceAll("!releaseRootDir!", configMap.releaseRootDir)
                                            .replaceAll("!WORKSPACE!", env.WORKSPACE)
                                        }
                params.add(new StringParameterValue('CAUSED_BY', env.BUILD_URL))
                dtMap["${variant}/${kT.tokenize('/')[-1]}"] = {
                    new buildTrigger().
                        build(job: kT,
                            wait: triggerJobConfig.wait?:false,
                            propagate: triggerJobConfig.propagate?:false,
                            timeOut: triggerJobConfig.timeOut?:7200,
                            parameters: params)
                }
            }

            if(variantConfig.dtJobs.parallel) {
                dtMap.failFast = true
                parallel dtMap
            } else {
                dtMap.each { dtK, dtV ->
                    Map dtSeq = [:]
                    dtSeq[dtK] = dtV
                    parallel dtSeq
                }
            }
        }
    }
}