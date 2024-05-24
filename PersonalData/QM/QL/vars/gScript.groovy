import com.eb.lib.jobUtils

def call(Map parameters) {
    def script = parameters.script
    def stage = parameters.stage
    def variant = parameters.variant
    def configMap = script.configMap
    def stageConfig = configMap.stageConfig
    def variantConfig = configMap.variantConfig[variant]
/***************************** End of common part *****************************/
    Boolean errorException = (variantConfig.errorException!=null)?variantConfig.errorException:
                                ((stageConfig.errorException!=null)?stageConfig.errorException:true)
    dir(variantConfig.workingDir) {
        docker.image(variantConfig.dockerImage).inside(variantConfig.dockerArgs) {
            if(variantConfig.runScript){
                new jobUtils().scriptRun(variantConfig.runScript, variantConfig.scriptNamePrefix, errorException)
            }
        }
    }
}