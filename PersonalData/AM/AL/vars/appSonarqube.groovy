import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config
  def configRuntime = body.script.configRuntime

  if (currentBuild.result == "SUCCESS") {
    node(configRuntime.leastloadednode) {
      timestamps {
        def launchersDir = "${configRuntime.workspace}/.launchers"
        if (!fileExists(launchersDir)) {
          dir "${configRuntime.workspace}/.launchers", {
            git branch: "${body.script.buildtools_branch}",
                    url: "${body.script.buildtools_url}"
          }
        } else {
          echo "Directory ${configRuntime.workspace}/.launchers already exists."
        }
      }
      try {
        dir "${configRuntime.app_workspace}", {
          echo "node name:" + configRuntime.leastloadednode
          echo "Sonarqube analysis and publish is starting for : ${GERRIT_PROJECT}"
          withSonarQubeEnv('SonarServer') {
            if (configRuntime.pipelineType.contains("master")) {
              sh(returnStdout: true, script: """#!/bin/bash
                                                source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && sonarqube_analysis ${VERBOSE} ${config.sonar_scanner} ${config.sonar_server} ${configRuntime.pipelineType} ${GERRIT_BRANCH} ${configRuntime.app_workspace} ${config.project} ${BUILD_URL} ${config.jacoco_report_path} """)
            } else{
              sh (returnStdout: true, script: """#!/bin/bash
                                                  source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && sonarqube_analysis ${VERBOSE} ${config.sonar_scanner} ${config.sonar_server} ${configRuntime.pipelineType} ${GERRIT_BRANCH} ${configRuntime.app_workspace} ${config.project} ${BUILD_URL} ${config.jacoco_report_path} ${GERRIT_CHANGE_NUMBER} ${GERRIT_PATCHSET_NUMBER} ${GERRIT_REFSPEC} """)
            }
          }
        }
      } catch(err) {
        commonlib.__INFO(err.toString())
        currentBuild.result = 'FAILURE'
        configRuntime.BUILD_STATUS = 'FAILURE'
        configRuntime.build_addinfo = "(Sonarqube analysis failed)!!!"
        configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
      } finally {
          try {
            if (configRuntime.pipelineType.contains("verify")) {
              sonarToGerrit(
              inspectionConfig: [
                    analysisStrategy: pullRequest()
                ],
                /* Optional parameters */
                reviewConfig: [
                  omitDuplicateComments: true,
                ]
              )
            }
            else {
              println "Skipping sonarToGerrit Trigger"
            }
          } catch (Exception errInFinally) {
            // Handle exceptions from the finally block
            commonlib.__INFO("An exception occurred in the finally block: " + errInFinally.toString())
        }
      }
    }
  }
}
