#!/bin/bash
rootRepo=T2K/qnx
input="httplinks"
while IFS= read -r url || [[ -n "$url" ]]; do
    repoPathSrc=$(echo "${url}" | sed "s/.*conti.de\///")
    repoPath=T2K/qnx/${repoPathSrc}
    repoName=$(echo "${repoPathSrc}" | sed "s/\//_/")
    echo "${repoName}: ${url} ----> ${repoPath}"
    if [ ! -d ${repoName} ]; then
        ssh -p 29418 t2k-gerrit gerrit create-project ${repoPath} </dev/null || true
        [ ! -d ${repoName} ] && git clone ${url} ${repoName}
        pushd ${repoName} &>/dev/null
        git remote update
        git push -f -o skip-validation --all ssh://t2k-gerrit.elektrobit.com:29418/${repoPath}
        popd &>/dev/null
    else
        pushd ${repoName} &>/dev/null
        git remote update
        echo "git push -f -o skip-validation --all ssh://t2k-gerrit.elektrobit.com:29418/${repoPath}"
        git push -f -o skip-validation --all ssh://t2k-gerrit.elektrobit.com:29418/${repoPath}
        popd &>/dev/null
    fi
    ssh -p 29418 t2k-gerrit gerrit set-project-parent "${repoPath}" --parent All-Devs+Conti </dev/null
done < "${input}"
ssh -p 29418 t2k-gerrit gerrit flush-caches --all