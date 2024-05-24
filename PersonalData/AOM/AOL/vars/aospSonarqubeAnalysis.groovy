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

  def aospSonarqubeParallelMap = [:]
  def sonarModules = ""

  node(configRuntime.pipeline_node) {
    target_id_list.each { target_id ->
      configRuntime.build_type_list = config[configRuntime.pipelineType][target_id]["build_type_list"].tokenize(" ")
      configRuntime.build_type_list.each { build_type ->
          if ( config["supported_target_ids"][target_id][configRuntime.pipelineType] == "disabled" ) {
            commonlib.__INFO("WARNING: " + configRuntime.pipelineType + " is disabled for " + target_id + ". If you want, you can enable it in " + PROJECT_CONFIG_FILE)
            return
          }
          make_targets = String.format("%s+%s+%s", "droid", config["supported_target_ids"][target_id]["custom_env_vars"], config.pipeline_make_targets)
          user_custom_build_env = config["supported_target_ids"][target_id]["user_custom_build_env"]
          lunch_target = String.format(config["supported_target_ids"][target_id]["lunch_target_template"], build_type)

          stackTarget = "StackTarget_${configRuntime.project_branch}_${target_id}_${build_type}"
          stackTargetFile = config.stacktarget_file
          build_target = [target_id,build_type].join("-")

          sonar_config=config.sonar_analysis?:''
          // if sonar_config is empty, then skip and return
          if (sonar_config == "") {
            return
          }
          // get sonar_pipeline_job from sonar.sonarqube_pipeline if defined else from sonar_config.sonar_freestyle_job  else through error
          sonar_pipeline_job = sonar_config.sonarqube_pipeline?:sonar_config.sonar_freestyle_job?:''
          if (sonar_pipeline_job == "") {
            commonlib.__ERROR("sonar_pipeline_job is empty. Please define sonar_pipeline_job or sonar_config.sonar_freestyle_job in config.sonar_analysis ")
            return
          }
          println("SONAR_JOB is: " + sonar_pipeline_job)
          def sonarAnalysis = config[configRuntime.pipelineType]["sonarAnalysis"]
          // Only for userdebug builds.
          if (config.sonar_analysis && config[configRuntime.pipelineType][target_id].run_sonar_analysis == "true" && build_type.contains("userdebug")) {

            sonar_volume=configRuntime.build_variant[build_target].source_volume + "_sonarqube"

            sonar_src_volume=configRuntime.build_variant[build_target].source_volume

            def wait_duration = sonarAnalysis["wait"].toBoolean()

            if (configRuntime.pipelineType.contains("verify")){
              node(configRuntime.build_variant[build_target].least_loaded_node) {
                dir(configRuntime.build_variant[build_target].source_volume){
                  //configRuntime.affected_projects setup this variable in preparePipeline.groovy 
                  // iterate over affected projects and find repo path for each project and check if sonar-project.properties file exists
                  // configRuntime.affected_projects is a map project as ker and valuue is an map again with refspec and files now iterate over this map
                  configRuntime.affected_projects.each { projectName, projectDetails ->

                    def repo_path = sh(script: "repo list -p ${projectName} 2>/dev/null || echo 'unknown_project'", returnStdout: true).trim()
                    if (repo_path == "unknown_project") {
                      commonlib.__INFO("Skipping Unknown_project from repo point of view: " + projectName)
                      return // continue equivalent
                    }
                    def sonar_project_properties = String.format('%s/%s',repo_path.trim(),"sonar-project.properties")
                    //'''find . -type f -name sonar-project.properties -printf "%h\n"'''
                    //run above find command to get the directory path of sonar-project.properties file in repo_path
                    find_command = String.format("find %s -type f -name sonar-project.properties -printf \"%%h\\n\"",repo_path.trim())               
                    def sonar_project_dir = sh(script: find_command, returnStdout: true).trim()
                    println("Checking if " + sonar_project_properties + " file exists for: " + repo_path)
                    if (fileExists(sonar_project_properties)) {
                      sonarModules += repo_path + ":" + projectDetails.refspec + " "
                    }else if (sonar_project_dir != "") {
                      commonlib.__INFO("Sonar-project.properties found in :" + sonar_project_dir + " for: " + repo_path)
                      sonarModules += sonar_project_dir + ":" + projectDetails.refspec + " "
                    }
                    else {
                      commonlib.__INFO("Skipping sonarqube analysis for: " + projectName + ".\nAs it does not have sonar-project.properties file")
                      return
                    }
                  }
                }
              }
              if (sonarModules == "") {
                commonlib.__INFO("Skipping SONARQUBE ANALYSIS for: " + GERRIT_CHANGE_NUMBER + ".\n As there are no sonar-project.properties file found in affected projects")
                return
              }
            }
            println "sonar modules:" + sonarModules.toString()
            sonar_job_parameters = [
                'BUILD_TOOL_URL='+           body.script.buildtools_url,
                'BUILD_TOOL_BRANCH='+        body.script.buildtools_branch,
                'DOCKER_IMAGE_ID='+          config.docker_image_id,
                'SONAR_SOURCE_VOLUME='+      sonar_volume,
                'LUNCH_TARGET='+             lunch_target,
                'RUN_SONAR_ANALYSIS='+       config[configRuntime.pipelineType][target_id].run_sonar_analysis,
                'SONAR_SERVER='+             sonar_config.sonar_server,
                'SONAR_BRANCH='+             configRuntime.project_branch,
                'SONAR_SCANNER='+            sonar_config.sonar_scanner,
                'SONAR_BUILD_WRAPPER='+      sonar_config.sonar_build_wrapper,
                'SONAR_PROJECTKEY_PREFIX='+  sonar_config.sonar_projectkey_prefix,
                'SNAPSHOT_NAME='+            configRuntime.project_release_version,
                'STACK_TARGET_LABEL='+       configRuntime.build_variant[build_target].least_loaded_node,
                'SONAR_MODULES='+            "${sonarModules.toString()}",
                'SONAR_ADDITIONAL_SOURCES='+ "${sonar_config.additional_sources}",
                'PIPELINE_TYPE='+             configRuntime.pipelineType,
                "CAUSED_BY="+                [JOB_NAME,BUILD_NUMBER].join("/"),
            ]
            // if pipeline type is snapshot and build_script is not empty from config.sonar_analysis
            if (configRuntime.pipelineType.contains("snapshot") && config.sonar_analysis?.build_script?:'' != "") {
              sonar_job_parameters += [
                  'BUILD_SCRIPT='+               config.sonar_analysis.build_script,
              ]
            }              
            sonar_parameters = [
                'LEAST_LOADED_NODE='+        configRuntime.build_variant[build_target].least_loaded_node,
                'SONAR_VOLUME='+             sonar_volume,
                'SONAR_SRC_VOLUME='+         sonar_src_volume,
                'SONAR_JOB='+                sonar_pipeline_job,
                'BUILD_TARGET='+             build_target,
            ]
            aospSonarqubeParallelMap[build_target] = commonlib.configureSonarqubeBuild(sonar_parameters,sonar_job_parameters,configRuntime.pipelineType,wait_duration)
          }
        }
    }
  }


  try {
    parallel aospSonarqubeParallelMap

    if (config[configRuntime.pipelineType].run_sonar_analysis == "true") {
      configRuntime.build_description = "SCA: +1 "
    }

  } catch (FlowInterruptedException e) {
      throw e
  } catch(err) {
    commonlib.__INFO(err.toString())
    // We ignore failure from sonarqube stage!
    configRuntime.build_addinfo = "(Sonarqube analysis failed)!!!"
  }

}
