def app_pipelinelib_bash_functions() {
return '''
#!/bin/bash
set -e
source $( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )/common.lib
# Rule: target id has to be same as manifest name.
# To check if commit is being supported in the manifest.
# First will check if user specify the target id (in case some target id not being used, but manifest still in the repo)
# Else try to get all manifest available.


code_change() {
    # This function will check that is there any code change or not
    local verbose=${1?verbose value required. Should be true or false}
    [[ $verbose == "true" ]] && set -x

    local workspace=${2?workspace required where new manifest file will be created}
    local prebuilt_app_workspace=${3?prebuilt_app_workspace required which contain old manifest file}

    cd ${workspace} && repo manifest -r -o ${workspace}/codeChange_manifest.xml
    if [ -f ${prebuilt_app_workspace}/manifest.xml ]; then
        diff ${workspace}/codeChange_manifest.xml ${prebuilt_app_workspace}/manifest.xml | grep 'path="app"' || true
    fi
    rm -rf ${workspace}/codeChange_manifest.xml
}

get_version() {
    # This function will return the versionCode for application
    local verbose=${1?verbose value required. Should be true or false}
    [[ $verbose == "true" ]] && set -x

    local workspace=${2?workspace required where app build will be run}
    local prebuilt_app_workspace=${3?prebuilt_app_workspace required which contain old manifest file}

    oldVersion=""
    if [ -f ${prebuilt_app_workspace}/version.txt ]; then
      oldVersion=$(cat ${prebuilt_app_workspace}/version.txt)
    fi

    # get year value as per this document:https://infohub.automotive.elektrobit.com/display/PRJMAGNASTEYRT2KB1/%5BDraft%5D+Application+versioning+And+Content
    year=$(date +%Y)
    if [[ ${year} -gt 2120 ]]; then
      echo "Only years between 2020 and 2120 are supported for this version scheme!!!"
      exit 1
    fi
    weekOfYear=$(date +%-V) # exclude 0 from for example week number 08. Otherwise, it is beyond octal range, resulting in error: (08: value too great for base (error token is "08")
    dayOfWeek=$(date +%u)
    versionString=$(($(($(($((${year} - 2020))*100000)) + (${weekOfYear}*1000))) + (${dayOfWeek}*100)))
    version=${versionString}
    # get build number per day
    if [ -f ${prebuilt_app_workspace}/version.txt ]; then
        if [[ ${versionString} == $((${oldVersion:0:-2}*100)) ]]; then
            buildPerDay=$((${oldVersion: -2}+1))
            version=$((${versionString} + ${buildPerDay}))
        fi
    fi
    echo "${version}"
}

app_ws_worker() {
    local verbose=${1?verbose value required. Should be true or false}
    [[ $verbose == "true" ]] && set -x

    local pipelineType=${2?pipelineType is required}
    local dev_env=${3?dev_env is required}
    local build_workspace=${4?build_workspace is required}
    local repo_manifest_url=${5?repo_manifest_url is required}
    local repo_manifest_revision=${6?repo_manifest_url is required}
    local repo_manifest_xml=${7?repo_manifest_xml is required}
    local sync_thread=2

    if [ ! -d "${build_workspace}" ]; then
      mkdir -p ${build_workspace}
    fi
    cd ${build_workspace}

    source ${dev_env}
    echo "repo init -u ${repo_manifest_url} -b ${repo_manifest_revision} -m ${repo_manifest_xml}"
    repo init -u ${repo_manifest_url} -b ${repo_manifest_revision} -m ${repo_manifest_xml}
    echo "repo forall -c "git reset --hard" "
    repo forall -c "git reset --hard"
    echo "repo sync -d -q --force-sync -j${sync_thread}"
    repo sync -d -q --force-sync -j${sync_thread}

    if [[ "$(toLowerCase ${pipelineType})" =~ .*verify.* ]]; then
        local gerrit_host=${8?gerrit_host is required}
        local gerrit_project=${9?gerrit_project is required}
        local gerrit_change_number=${10?gerrit_change_number is required}
        local gerrit_patchset_number=${11?gerrit_patchset_number is required}
        local dependencies=${12}
        local download_type=$(get_download_type ${gerrit_host} ${gerrit_change_number} ${gerrit_project})
        set -x

        # download triggered change
        repo download ${download_type} "${gerrit_project}" "${gerrit_change_number}/${gerrit_patchset_number}"

        #download dependencies related with triggered change
        for dep in ${dependencies[@]}; do
            local dp=$(echo ${dep} | cut -d, -f1)
            local patchset_number=$(echo ${dep} | cut -d, -f2)
            dp_project=$(get_project_from_change_number ${gerrit_host} ${dp})
            repo download ${download_type} "${dp_project}" "${dp}/${patchset_number}"
        done
        set +x
    fi
}

app_build() {
    # This function is used to build an app project
    local verbose=${1?verbose value required. Should be true or false}
    [[ $verbose == "true" ]] && set -x

    local workspace=${2?workspace required where app build will be run}
    local devenvDir=${3?devenvDir with env_setup.sh is required}
    local pipelineType=${4?pipelineType is required}
    local version=${5}
    local branchIdentifier=${6}

    # Check, if remote repositores are set
    pushd ${workspace}/app &>/dev/null
    local GERRIT_PROJECT_URL=$(git remote get-url $(git remote))
    local GERRIT_PROJECT=${GERRIT_PROJECT_URL##*/}
    echo "----------------Gerrit_project from build: ${GERRIT_PROJECT} -----------------"
    if [[ ${GERRIT_PROJECT} == *"HMI"* ]]; then
        echo "External repositories check has been neglected for HMI project"
    else
        if grep -Przo "(?s)repositories {.*?(jcenter|maven|google)\\(\\).*?}" --include="*.gradle" ${workspace}; then
            set +x
            echo "
        ============================================================
        Warning: External repositories are only allowed for HMI project. Failing build.
        ============================================================
            "
            set -x
            exit 1
        fi
    fi
    # Check Environment
    if [[ "x${devenvDir}" = "x" ]]; then
        echo "devevnvDir not set. Please set to correct devenv to use"
        exit 1
    fi
    if [[ "x${workspace}" = "x" ]]; then
        echo "Please define the workspace"
        exit 1
    fi
    if [[ "$(toLowerCase ${pipelineType})" =~ .*master.* ]]; then
        build_parameters="-PversionString=${version} -PbranchIdentifier=${branchIdentifier}"
    else
        build_parameters=""
    fi

    gradleCfg="${workspace}/.gradle"
    mkdir -p ${gradleCfg}

    # get repo name of built project and create link
    current_repo=$(basename $(git remote -v | awk '{print $2}'))
    popd &>/dev/null
    cd ${workspace}
    ln -sfT app ${current_repo}
    set +x
    source ${devenvDir}
    # Get Devenv version info
    devenv -v > ${workspace}/devenv_version.txt || true
    set -x

    # Default gradle parameters used for all builds
    gradle_parameters="--refresh-dependencies --project-cache-dir ${workspace}/gradle_cache --gradle-user-home ${gradleCfg} "
    # Since the gradle user home is set to the current workspace, we need to ensure that
    # a gradle.properties is available
    cp ~/.gradle/gradle.properties ${gradleCfg}
    # Config for gradle targets
    buildtargets="clean assemble"
    # Clean up old stuff
    ## TODO need to check cleanup stuff before starting the build
    outFiles=$(ls ${workspace}/*.output 2> /dev/null | wc -l)
    if [[ "${outFiles}" != "0" ]]; then
        rm ${workspace}/*.output
    fi
    logFiles=$(ls ${workspace}/*.log 2> /dev/null | wc -l)
    if [[ "${logFiles}" != "0" ]]; then
        rm ${workspace}/*.log
    fi
    # Write keystore information
    gradle_file="${workspace}/app/build.gradle"
    ## TODO need to check
    if grep signingConfigs.platform ${gradle_file}; then
        echo "DEBUG=platform.keystore" > ${workspace}/keystore_info.txt;
        echo "RELEASE=platform.keystore" >> ${workspace}/keystore_info.txt;
    else
        echo "DEBUG=debug.keystore" > ${workspace}/keystore_info.txt;
        echo "RELEASE=debug.keystore" >> ${workspace}/keystore_info.txt;
    fi
        
    # Start the build of the app
    echo "---- Building app - will also build all dependencies ----"
    pushd ${workspace}/app &>/dev/null
    echo "build url: ${BUILD_URL}" > ${workspace}/build_${BUILD_NUMBER}.output
    (set -o pipefail; gradle ${build_parameters} ${buildtargets} ${gradle_parameters} 2>&1 |& tee -a ${workspace}/build_${BUILD_NUMBER}.output )
    popd &>/dev/null
}

publish_to_git(){
    # This function is used to publish the generated and tested apks to git binary repo
    # This is also used to publish the 3rd party apps
    local verbose=${1?verbose value required. Should be true or false}
    [[ $verbose == "true" ]] && set -x

    local app_workspace=${2?app_workspace required where app build is executed}
    local prebuilt_app_workspace=${3?prebuilt_app_workspace required binary apks dir}
    local branch=${4?branch required to push the changes}
    local publish_app=${5}

    direct_push="false"
    workspace=$(dirname ${app_workspace})
    if [ -f "${prebuilt_app_workspace}/.ci_direct_push.txt" ]; then
        direct_push="true"
        cd ${workspace}
        repo manifest -r -o temp_manifest.xml
        GERRIT_PROJECT=$(cd ${app_workspace} && repo list -n $PWD)
        GERRIT_NEWREV=$(cd ${app_workspace} && git log -1 --pretty=format:%H)
        VERSION=$(cat ${workspace}/version.txt)

        if [ -f ${prebuilt_app_workspace}/manifest.xml ]; then
            commitSHA=$(repo diffmanifests --raw  ${workspace}/temp_manifest.xml ${prebuilt_app_workspace}/manifest.xml | grep '^C app' | cut -d' ' -f3-)
            if [[ "x${commitSHA}" == "x" ]]; then
              commit_msg="no source code change"
            else
              commitSHA=( $commitSHA )
              commit_msg=$(cd ${app_workspace} && git log --oneline ${commitSHA[1]}..${commitSHA[0]} | cut -d' ' -f2-)
            fi
        else
             commit_msg="no source code change"
        fi
        commit_msg="${commit_msg}
            GERRIT_PROJECT :  ${GERRIT_PROJECT}
            GERRIT_NEWREV  :  ${GERRIT_NEWREV}
            GERRIT_BRANCH  :  ${branch}
            VERSION        :  ${VERSION}
            BUILD_NUMBER   :  ${BUILD_NUMBER}"
    else
        commit_msg="Integrate 3rd party app"
    fi
    if [ -d ${prebuilt_app_workspace} ]; then
        cd ${prebuilt_app_workspace}
        git checkout ${branch}
        if [[ "${publish_app}" != "" ]]; then
            apk_list=""
            for apk in ${publish_app}; do
                app_path=$(find ${app_workspace} -type f  -name ${apk})
                apk_list="${apk_list} ${app_path}"
            done
        else
            apk_list=$(find ${app_workspace} -type f  -name "*.apk")
        fi

        echo "copied applications: ${apk_list}"
        cp -r ${apk_list} ${prebuilt_app_workspace}
        cp -r ${workspace}/devenv_version.txt ${prebuilt_app_workspace}/devenv_version.txt
        #publish manifest file to prebuilt_module/lib projects
        cp ${workspace}/version.txt ${prebuilt_app_workspace}
        cp ${workspace}/branch_identifier.txt ${prebuilt_app_workspace}

        cd ${prebuilt_app_workspace}
        if git status --porcelain |grep .; then
            git_push ${prebuilt_app_workspace} ${branch} "${commit_msg}" "${direct_push}"

            if ! [[ "${prebuilt_app_workspace}" =~ .*sony.* ]]; then
                cd ${workspace}
                repo manifest -r -o ${prebuilt_app_workspace}/manifest.xml
                commit_msg="${VERSION}: Add manifest file"
                cd ${prebuilt_app_workspace}
                if git status --porcelain |grep .; then
                    git_push ${prebuilt_app_workspace} ${branch} "${commit_msg}" "${direct_push}"
                    git tag -a ${VERSION} -m ${VERSION}
                    git push origin ${VERSION}
                else
                    echo "Nothing commited to git!!!"
                fi
            fi
        else
            echo "Nothing commited to git!!!"
        fi
    else
        echo "${prebuilt_app_workspace} is not available. Please add Binary repo project into AppsManifests and restart the master build"
        exit 1
    fi
}

publish_to_artifactory() {
    # This function is used to publish the lib to artifactory
    local verbose=${1?verbose value required. Should be true or false}
    [[ $verbose == "true" ]] && set -x

    local dev_env=${2?dev_env required to get the gradle}
    local app_name=${3?app_name required where app build is executed}
    local build_workspace=${4?build_workspace required for gradle folder path}
    local version=${5?version required to publish the app with proper version}

    source ${dev_env}
    # gradle parameters to run the build
    gradle_parameters="--refresh-dependencies
                    --project-cache-dir ${build_workspace}/gradle_cache
                    --gradle-user-home ${build_workspace}/.gradle
                    -PbuildEnvironment=prod
                    -PbuildNumber=${version}
                    "

    # clean should NOT be called here, because we want to reuse aars that were created during build step
    build_targets="artifactoryPublish"

    # Start the build of the app
    publish_to_artifactory=true
    diff_path="/net/deulmhustorage/tmp/diff_checking/${JOB_NAME}/${app_name}/"
    if ls ${build_workspace}/app/build/outputs/aar/*.aar &>/dev/null; then
        if [ -d ${diff_path} ]; then
            DEBUG_DIFF=$(python ${WORKSPACE}/.launchers/libtools/pipeline/jardiff.py ${build_workspace}/app/build/outputs/aar/*-debug.aar ${diff_path}*-debug.aar)
            RELEASE_DIFF=$(python ${WORKSPACE}/.launchers/libtools/pipeline/jardiff.py ${build_workspace}/app/build/outputs/aar/*-release.aar ${diff_path}*-release.aar)
            if [ "$DEBUG_DIFF" ] || [ "$RELEASE_DIFF" ]; then
                echo "---- Changes detected. Publishing artifacts to Artifactory... ----"
            else
                echo "---- No changes in .aar files detected. Nothing to publish to Artifactory. ----"
                publish_to_artifactory=false
            fi
        fi
    fi
    if ${publish_to_artifactory}; then
        echo "---- Publishing artifacts ----"
        cd ${build_workspace}/app  && repo start aed2_eb_1_0 && gradle ${build_targets} ${gradle_parameters}
        cd ${build_workspace}
        if [ ! -d $(dirname ${diff_path}) ]; then
            mkdir -p ${diff_path}
        fi
    fi
    cp ${build_workspace}/app/build/outputs/aar/*.aar ${diff_path}
}

git_push(){
    # This function is used to publish the generated and tested apks to git binary repo
    local project_workspace=${1?project_workspace required to push from that project dir}
    local branch=${2?branch required to push the change}
    local commit_msg=${3?commit_msg required to push the changes}
    local direct_push=${4?direct_push is required to push without review:true/false}
    local gerrit_reviewer_list=${5}

    pushd ${project_workspace} &>/dev/null
    git add -A
    git status
    if git status --porcelain |grep .; then
        git commit -m "${commit_msg}"
        if [[ "${direct_push}" == "true" ]]; then
            git push origin ${branch}
        else
            gerrit_reviewer="%"
            if [[ "${gerrit_reviewer_list}" != "" ]]; then
                for item in $gerrit_reviewer_list; do
                    gerrit_reviewer=$gerrit_reviewer"r=${item},"
                done
            fi
            git push origin HEAD:refs/for/${branch}${gerrit_reviewer%?}
        fi
    else
        echo "nothing to commit !!!"
    fi
    popd &>/dev/null
}

aosp_preinstalled_configuration(){
    # This function will check if any preinstalled_app doesn't have *Android.mk file
    local verbose=${1?verbose value required. Should be true or false}
    [[ $verbose == "true" ]] && set -x

    local prebuilt_app_workspace=${2?prebuilt_app_workspace required binary apks dir}
    local branch=${3?branch required to push the changes}
    local aosp_project=${4?aosp_project is required to integrate app into aosp}
    local aosp_preinstalled_app=${5}
    local aosp_prebuilt_make_file=${6?aosp_prebuilt_make_file}
    local template_make_file=${7?template_make_file}
    local gerrit_reviewer=${8?gerrit_reviewer}

    new_app="false"
    commit_msg="Add prebuilt configuration for"
    # generate Android.mk file in case it's required
    if [[ "${aosp_preinstalled_app}" != "" ]]; then
        apps=""
        for apk in ${aosp_preinstalled_app}; do
            if [[ "${apk}" =~ .*debug.* ]] || [[ "${apk}" =~ .*release.* ]]; then
                apk=${apk%-*}
            else
                apk=${apk%.*}
            fi
            mk_file="${apk}.app.Android.mk"
            if [ ! -f ${prebuilt_app_workspace}/${mk_file} ]; then
                generate_android_make_file ${verbose} ${prebuilt_app_workspace} ${apk} ${mk_file} ${template_make_file}
                commit_msg="${commit_msg} ${apk}"
                apps="${apps} ${apk}"
            fi
        done

        # add preinstalled app into aosp project and push for review
        if [[ "${apps}" != "" ]]; then
            commit_msg="Integrate 3rd party app: ${apps}"

            #commit *.Android.mk file into app prebuild project
            git_push ${prebuilt_app_workspace} ${branch} "${commit_msg}" "false" "${gerrit_reviewer}"

            pushd $(dirname ${prebuilt_app_workspace}) &>/dev/null
            git clone "${aosp_project}" -b ${branch} aosp_project
            pushd aosp_project &>/dev/null
            for app in ${apps}; do
                if ! grep "PRODUCT_PACKAGES.* ${app}" ${aosp_prebuilt_make_file}; then
                    sed -i "s/PRODUCT_PACKAGES += .*/& ${app}/" ${aosp_prebuilt_make_file}
                fi
            done
            git_push $(dirname ${prebuilt_app_workspace})/aosp_project ${branch} "${commit_msg}" "false" "${gerrit_reviewer}"
            popd &>/dev/null
            popd &>/dev/null
        fi
    fi
}

generate_android_make_file(){
    # This function will be used to generate required Android.mk file to prebuilt_workspace to integrating the apk into snapshot
    local verbose=${1?verbose value required. Should be true or false}
    [[ $verbose == "true" ]] && set -x

    local prebuilt_app_workspace=${2?prebuilt_app_workspace required to generate the Android.mk in this dir}
    local app=${3?app required to create android.mk file for that app}
    local mk_file=${4?mk_file required to get android.mk file name}
    local template_make_file=${5?template_make_file}

    cp $(dirname ${prebuilt_app_workspace})/${template_make_file} ${prebuilt_app_workspace}/${mk_file}
    sed -i "s/__APP_NAME__/${app}/" ${prebuilt_app_workspace}/${mk_file}
}
'''
}

return this;
