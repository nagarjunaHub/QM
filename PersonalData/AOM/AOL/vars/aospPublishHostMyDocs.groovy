import hudson.model.*
import com.eb.lib.aosp.aospUtils
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import com.eb.lib.aosp.PipelineEnvironment

def call(body) {
    // For pipeline common config
    def commonlib = new aospUtils()
    def config = body.script.config

    def configRuntime = body.script.configRuntime
    def target_id = config.publish_doc_target.split("-")[0]
    node(configRuntime.build_variant[config.publish_doc_target].least_loaded_node) {
        // get project list which has change in this snapshot
        try {
            source_volume = configRuntime.build_variant[config.publish_doc_target].source_volume
            repo_manifest_release_revision = configRuntime.repo_rel_manifest_revision
            repo_manifest_release = config.repo_release_manifest_url
            changed_projects = sh(returnStdout:true, script: """#!/bin/bash -e
                            source ${new PipelineEnvironment(this).loadBashLibs()} &&   getChangedProjects ${VERBOSE} ${source_volume} ${repo_manifest_release_revision} ${repo_manifest_release} ${target_id}""")

            // create version, generate doc and publish to HostMyDocs
            if (changed_projects) {
                changed_projects.split("\n").each { project ->
                    if (project.contains("vendor/elektrobit")) {
                        def build_workspace = source_volume + "/" + project
                        def module_name = project.split('/')[-1]
                        println module_name

                        dir(source_volume) {
                            // check if changes_project has a index.adoc file
                            def adoc_file = "src/docs/asciidoc/index.adoc"
                            if (fileExists("${source_volume}/${project}/${adoc_file}")) {
                                println "${adoc_file} is available for ${project}"
                                // if adoc_file is there then generate the version to upload the document
                                version = sh(returnStdout: true, script: """#!/bin/bash -e
                                            source ${new PipelineEnvironment(this).loadBashLibs()} && getAospLibVersion ${VERBOSE} ${build_workspace} ${config.branch_identifier_version}""").trim()
                                println "version for ${project}:" + version
                                // get the module name as document name for publishing
                                dev_env = config.dev_env
                                // generate index.html file using index.adoc file inside docker
                                docker.image("${config.docker_image_asciidoc}").inside("-e HOME=${env.HOME} -v ${source_volume}:/app-src -v ${dev_env}:${dev_env}") {
                                    commonlib.onHandlingException("generate adoc for ${project}") {
                                        try {
                                            sh(returnStdout: true, script: """#!/bin/bash -ex
                                                                             source ${dev_env}/env_setup.sh
                                                                             cd /app-src/${project}
                                                                             echo "----------- start: generation of adoc for ${project} -----------"
                                                                             adoc -i /app-src/${project}/\$(dirname ${adoc_file}) -o /app-src/out/hostmydocs/${module_name} || true 
                                                                             echo "----------- end: generation of adoc for ${project} -----------" """
                                            )
                                        } catch (err) {
                                            catchError(buildResult: currentBuild.result, stageResult: currentBuild.result) {
                                                currentBuild.result = "FAILURE"
                                                error("FAIL Build early due to :" + err.getMessage())
                                            }
                                        }
                                    }
                                }
                                // publish document to HostMyDocs
                                sh(returnStdout: true, script: """#!/bin/bash -e
                                            source ${new PipelineEnvironment(this).loadBashLibs()} && upload_doc ${VERBOSE} ${source_volume} ${module_name} ${config.hostMyDocs} ${version}""").trim()

                            } else {
                                println "index.adoc file is not available for ${project}"
                            }
                        }
                    }
                }
            } else {
                println "No changed projects available to run PublishHostMyDocs Stage"
            }
        } catch (FlowInterruptedException e) {
            throw e
        } catch (err) {
            configRuntime.stage_results[STAGE_NAME] = "FAILURE"
            commonlib.__INFO(err.toString())
            currentBuild.result = 'FAILURE'
            configRuntime.BUILD_STATUS = 'FAILURE'
            configRuntime.stage_results[STAGE_NAME] = "FAILURE"
            configRuntime.build_addinfo = "(Publish AOSP Documents)!!!"
            configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
        }
    }
}
