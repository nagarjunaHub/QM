import com.eb.lib.jobUtils
import com.eb.lib.btrfsUtils

def call(Map parameters) {
    def script = parameters.script
    def stage = parameters.stage
    def variant = parameters.variant
    def configMap = script.configMap
    def stageConfig = configMap.stageConfig
    def variantConfig = configMap.variantConfig[variant]
/***************************** End of common part *****************************/
    Boolean alwaysClean = (variantConfig.alwaysClean!=null)?variantConfig.alwaysClean:
                                ((stageConfig.alwaysClean!=null)?stageConfig.alwaysClean:true)
    dir(variantConfig.workingDir) {
        docker.image(variantConfig.dockerImage).inside(variantConfig.dockerArgs) {
            if(variantConfig.runScript){
                new jobUtils().scriptRun(variantConfig.runScript, variantConfig.scriptNamePrefix)
            }
        }
    }
    new btrfsUtils().cleanUpSubvolumeWS(variantConfig.workingDir, variantConfig.subvolumeBaselineDir, alwaysClean)
}