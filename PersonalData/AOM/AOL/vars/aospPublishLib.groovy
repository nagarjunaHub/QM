import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
def call(body) {
    // For pipeline common config
    def commonlib = new aospUtils()
    def config = body.script.config
    def configRuntime = body.script.configRuntime
    def target_id_list = configRuntime.supported_targets.tokenize(" ")
    def aospPublishLibParallelMap = [:]
    node(configRuntime.pipeline_node) {
        target_id_list.each { target_id ->
            def build_type_list = config[configRuntime.pipelineType][target_id]["build_type_list"].tokenize(" ")
            build_type_list = (build_type_list.contains("user") && build_type_list.contains("userdebug")) ? ['user'] : ['userdebug']
            build_type_list.each { build_type ->
                if ( config["supported_target_ids"][target_id][configRuntime.pipelineType] == "disabled" ) {
                    commonlib.__INFO("WARNING: " + configRuntime.pipelineType + " is disabled for " + target_id + ". If you want, you can enable it in " + PROJECT_CONFIG_FILE)
                    return
                }
                lunch_target = String.format(config["supported_target_ids"][target_id]["lunch_target_template"], build_type)
                stackTarget = "StackTarget_${configRuntime.project_branch}_${target_id}_${build_type}"
                stackTargetFile = config.stacktarget_file
                build_target = [target_id,build_type].join("-")
                group_Id = "com.elektrobit.${config.project_line}"
                if (config[configRuntime.pipelineType][target_id].run_lib_upload == "true") {

                    Lib_parameters = [
                            'LEAST_LOADED_NODE=' +               configRuntime.build_variant[build_target].least_loaded_node,
                            'SRC_VOLUME=' +                       configRuntime.build_variant[build_target].source_volume,
                            'BUILD_TARGET=' +                     build_target,
                            'TARGET_ID='+                         target_id,
                            'REPO_MANIFEST_RELEASE='+             config.repo_release_manifest_url,
                            'REPO_MANIFEST_RELEASE_REVISION='+    configRuntime.repo_rel_manifest_revision,
                            'DEV_ENV='+                           config.dev_env,
                            'BRANCH_VERSION='+                    config.branch_identifier_version,
                            'SCRIPT_PATH=' +                      config.artifactoryscript_path,
                            'ARTIFACT_REPO='+                     config.artifactoryPublishRepo,
                            'GROUP_ID='+                          group_Id
                    ]
                    aospPublishLibParallelMap[build_target] = commonlib.configureAospLibUpload(Lib_parameters)
                  }
              }
          }
        }
    try {
        parallel aospPublishLibParallelMap
    } catch (FlowInterruptedException e)  {
        throw e
    } catch(err) {
        commonlib.__INFO(err.toString())
        currentBuild.result = 'FAILURE'
        configRuntime.BUILD_STATUS = "FAILURE"
        configRuntime.stage_results[STAGE_NAME] = "FAILURE"
        configRuntime.build_addinfo = "(Publish Lib to artifactory failed)!!!"
        configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
    }
}