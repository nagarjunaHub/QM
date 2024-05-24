import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config
  def configRuntime = body.script.configRuntime

  if (configRuntime.BUILD_STATUS == "SUCCESS" && configRuntime.TEST_STATUS == "SUCCESS") {
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
        echo "publish to binary repo"
        configRuntime.build_addinfo = "(Publish)!!!"
        if (configRuntime.deploy_to_git == 'true') {
          commonlib.__INFO("publish to git prebuilt repository started")
          def jenkinsRepoName = buildtools_url.tokenize('/').last()
          def jenkinsPipelineInfo = commonlib.getRepositoryRevisionUrl("${jenkinsRepoName}", config.jenkins_script_repo_url).trim()
          def pipelineLibraryInfo = commonlib.getRepositoryRevisionUrl("pipeline-global-library", config.jenkins_script_repo_url).trim()
          sh(returnStdout: true, script: """#!/bin/bash
                                          source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && publish_to_git ${VERBOSE} ${configRuntime.app_workspace} ${configRuntime.prebuilt_app_workspace} ${GERRIT_BRANCH} ${config.branch_identifier} ${config.dev_env} \"${configRuntime.publish_app}\" "${jenkinsPipelineInfo}" "${pipelineLibraryInfo}" """)
          commonlib.__INFO("publish app change_number and patchset_number to a file")
          sh(returnStdout: true, script: """#!/bin/bash
                                          source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib  && publish_commitInfo ${VERBOSE} ${configRuntime.app_workspace} ${configRuntime.prebuilt_app_workspace} ${GERRIT_BRANCH} ${config.gerrit_host} ${config.repo_release_manifest_url} ${config.repo_manifest_xml} ${config.net_sharedrive} """)
        }
        if (configRuntime.deploy_to_artifactory == 'true') {
          commonlib.__INFO("publish to Artifactory started")
          sh(returnStdout: true, script: """#!/bin/bash
                                          source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && publish_to_artifactory ${VERBOSE} ${config.dev_env} ${configRuntime.project} ${configRuntime.build_workspace} ${GERRIT_BRANCH} "${configRuntime.buildPerDay}" ${configRuntime.force_publish} """)
        }

        /*if(depoy_to_documentation) {
           sh(returnStdout: true, script: """#!/bin/bash
                       source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && source ${dev_env} && publish_to_documentation ${build_workspace} ${dev_env} "${gradle_prop_path}" """)
        */
        if (configRuntime.deploy_to_documentation == 'true') {
          commonlib.__INFO("create and publish documents to documentation server started")
          commonlib.__INFO("create and publish javadoc to documentation server started")
          sh(returnStdout: true, script: """#!/bin/bash
                                          source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && source ${config.dev_env} && publish_javadoc ${VERBOSE} ${config.dev_env} ${configRuntime.build_workspace} ${config.hostMyDocs} ${configRuntime.project} ${GERRIT_BRANCH} "${config.branch_identifier}" "${configRuntime.buildPerDay}" """)
          commonlib.__INFO("create and publish asciidoc to documentation server started")
          sh(returnStdout: true, script: """#!/bin/bash
                                          source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && source ${config.dev_env} && publish_asciidoc ${VERBOSE} ${config.dev_env} ${configRuntime.build_workspace} ${config.hostMyDocs} ${configRuntime.project} ${config.asciidoc_docker_image} ${GERRIT_BRANCH} "${config.branch_identifier}" "${configRuntime.buildPerDay}" """)
        }
        // as per new process to integrate app this step will be removed in near future
        // need to also remove variable: configRuntime.aosp_preinstalled_app, template_make_file, aosp_prebuilt_make_file, config.gerrit_reviewer_third_patry_app
        /*if (configRuntime.aosp_preinstalled_app) {
          configRuntime.build_addinfo = "(prebuilt app configuration)!!!"
          sh(returnStdout: true, script: """#!/bin/bash
                                source ${configRuntime.workspace}/.launchers/libtools/pipeline/app_pipeline.lib && aosp_preinstalled_configuration ${VERBOSE} ${configRuntime.prebuilt_app_workspace} ${GERRIT_BRANCH} \"${config.aosp_project}\" \"${configRuntime.aosp_preinstalled_app}\" ${config.aosp_prebuilt_make_file} ${config.template_make_file} \"${config.gerrit_reviewer_third_patry_app}\" """)
        }*/
      } catch(err) {
        commonlib.__INFO(err.toString())
        currentBuild.result = 'FAILURE'
        configRuntime.PUBLISH_STATUS = 'FAILURE'
        configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
      }
    }
  }
}

