import os,sys,re, glob
import subprocess, multiprocessing
import shutil
import datetime
import yaml
import argparse

scriptroot = os.path.dirname(os.path.realpath(sys.argv[0]))
parser = argparse.ArgumentParser(prog=sys.argv[0], description="To switch to a newer QNX IIP baseline.")
parser.add_argument("-c", "--config", dest="config_file", action="store", required=False, default=os.path.join(scriptroot,"configs/t2k.yml"),
                    help="(-c|--config) Project config file in yaml format")
parser.add_argument("--dry-run", dest="dryrun", action="store_true", required=False,
                    help="(--dryrun) Dryrun will not commit anything")
args = parser.parse_args()

gerrit_list_links = os.path.join(os.path.dirname(scriptroot),'gerrit', 'list_links')
conti_server_list = ['wetp715x.we.de.conti.de:29418', 'git-id.conti.de', 'github.conti.de', 'github-vni.geo.conti.de', 'fr01891vmx.fr.ge.conti.de:29418', 'buic-scm-fr.contiwan.com:29418']
with open(args.config_file, 'r') as fconfig:
    try:
        configs = yaml.safe_load(fconfig)
    except yaml.YAMLError as exc:
        print(exc)

inrelease = os.path.join('/tmp', '.inrelease')
outrelease = os.path.join('/tmp', '.outrelease')
acrelease = os.path.join('/tmp', '.acrelease')

global log_output
currtime = datetime.datetime.now().strftime("%Y%m%d-%H:%M:%S")
log_file_path = os.path.join(scriptroot,currtime+'.log')


def execute(cmd, shell=False, log=False):
    output = ""
    if log:
        if shell:
            print(cmd)
        else:
            print(" ".join(cmd))
    with subprocess.Popen(cmd, stdout=subprocess.PIPE, bufsize=1, universal_newlines=True, shell=shell) as p:
        for line in p.stdout:
            if log:
                print(line, end='')
            output += line

    if p.returncode != 0:
        raise subprocess.CalledProcessError(p.returncode, p.args)
    return output

def get_ssh_url(repo_name):
    return "ssh://{SERVER}:{PORT}/{REPONAME}".format(SERVER=configs["server"]["gerrit"], PORT=configs["server"]["gerrit_port"], REPONAME=repo_name)

def commit_push(gitdir, gitbranch, topic, *args):
    commit_msg_list = list()
    execute(['git', '--git-dir={}/.git'.format(gitdir), '--work-tree={}'.format(gitdir), 'add', '.'])
    for msg in list(args):
        commit_msg_list += ["-m {}".format(msg)]
    execute(['git', '--git-dir={}/.git'.format(gitdir), '--work-tree={}'.format(gitdir), 'commit'] + commit_msg_list)
    execute("scp -p -P 29418 t2k-gerrit.elektrobit.com:hooks/commit-msg {}/.git/hooks/".format(gitdir), shell=True)
    execute(['git', '--git-dir={}/.git'.format(gitdir), '--work-tree={}'.format(gitdir), 'commit', '--amend', '--no-edit'])
    execute(['git', '--git-dir={}/.git'.format(gitdir), '--work-tree={}'.format(gitdir), 'push', 'origin', 'HEAD:refs/for/{}%topic={}'.format(gitbranch,topic)])
    #execute(['git', '--git-dir={}/.git'.format(gitdir), '--work-tree={}'.format(gitdir), 'push', 'origin', 'HEAD:refs/for/{}%topic={}'.format("tmp/q_test",topic)])

def get_latest_commit_id(gitdir):
    return execute(["git","--git-dir={}/.git".format(gitdir), '--work-tree={}'.format(gitdir),"log", "-1", "--pretty=format:\"%h\""])

def iip2t2k_manifest_porter(oldbaseline, newbaseline, push_commit=True):
    composer = ""
    if os.path.exists(inrelease) and os.path.isdir(inrelease):
        shutil.rmtree(inrelease)
    if os.path.exists(outrelease) and os.path.isdir(outrelease):
        shutil.rmtree(outrelease)
    execute(["git", "clone", get_ssh_url(configs["iip"]["manifest_repo"]), "-b", configs["iip"]["baseline_branch"], inrelease])
    execute(['git', '--git-dir={}/.git'.format(inrelease), '--work-tree={}'.format(inrelease), 'checkout', newbaseline])
    execute(["git", "clone", get_ssh_url(configs["project"]["manifest_repo"]), "-b", configs["project"]["baseline_branch"], outrelease])
    with open(os.path.join(inrelease,configs["iip"]["manifest_file"]), 'r') as infile:
        for line in infile:
            if not re.search("<default\sremote=\"scm\".*sync-j=\"10\"/>",line) and not re.search(".*meta-sw-caaf-android.*",line):
                line = re.sub("<remote fetch=\".*\"\sname=", "<remote fetch=\"ssh://" + configs["server"]["gerrit"] + ":" + str(configs["server"]["gerrit_port"]) + "/\" name=",line) 
                line = re.sub("review=\"ssh:\/\/.*.conti.de:29418\/", "review=\"ssh://" + configs["server"]["gerrit"] + ":" + str(configs["server"]["gerrit_port"]) + "/",line)
                if re.search("<project\s.*groups=.*name=",line):
                    line = re.sub("name=\"", "name=\"" + configs["project"]["prefix_repo_mirror"] + "/",line)
                composer += line

    with open(os.path.join(outrelease,newbaseline + ".xml"), 'w+') as outfile:
        outfile.write(composer)
    if os.path.isfile(os.path.join(outrelease,newbaseline + ".xml")):
        os.remove(os.path.join(outrelease,oldbaseline + ".xml"))

    composer = ""
    with open(os.path.join(outrelease,configs["project"]["manifest_file"]), 'r') as outfile:
        for line in outfile:
            if re.search("<include\sname=\"{}".format(oldbaseline),line):
                line = line.replace(oldbaseline,newbaseline)
            composer += line
    with open(os.path.join(outrelease,configs["project"]["manifest_file"]), 'w') as outfile:
        outfile.write(composer)
    if push_commit:
        commit_push(outrelease, configs["project"]["baseline_branch"], newbaseline, "Integrate {}".format(newbaseline), "Tracing-Id: {}".format(configs["project"]["jira_ticket"]))
    return get_latest_commit_id(outrelease)

def fetching_workspace(ws):
    if not os.path.exists(ws):
        os.mkdir(ws)
    os.chdir(ws)
    if  os.path.exists(".repo/manifests"):
        try:
            execute(['repo', 'forall', '-vc', 'git reset --hard HEAD'])
            execute(['repo', 'forall', '-vc', 'git clean -dfx'])
        except:
            pass
        shutil.rmtree(".repo/manifests")
        os.remove(".repo/manifest.xml")
    execute(['repo', 'init', '-u','ssh://{SERVER}:{PORT}/{REPONAME}'.format(SERVER=configs["server"]["gerrit"], PORT=configs["server"]["gerrit_port"], REPONAME=configs["project"]["manifest_repo"]), '-b', configs["project"]["baseline_branch"], '-m', configs["project"]["manifest_file"]])
    for xmlfile in glob.glob(os.path.join(outrelease, '*.xml')):
        shutil.copy(xmlfile, '.repo/manifests/')
    execute(['repo', 'sync', '-d', '-j4'])
    print("######### END: FETCHING-WORKSPACE #########")


def detect_ac_img_new_repo(ws):
    new_repo_list = list()
    l_output = ""
    if os.path.exists(ws) and os.path.isdir(ws):
        shutil.rmtree(ws)
    execute(["git", "clone", get_ssh_url(configs["iip"]["ac_image_repo"]), "--single-branch", "-b", configs["iip"]["ac_image_branch"], ws])
    os.chdir(ws)
    print("\n\n\n######### START: AC-IMAGE-REPOS-CHECK #########")
    l_output += '\n'+ "######### START: AC-IMAGE-REPOS-CHECK #########"

    for file in glob.glob(os.path.join(ws, '*.xml'), recursive=False):
        with open(file, 'r') as mf:
            for pl in mf:
                if re.search("<project\s.*name=",pl):
                    linecheck = pl.split("name=")[-1].split(" ")[0].replace("\"","")
                    is_new_repo = True
                    with open(gerrit_list_links, 'r') as infile:
                        for inline in infile:
                            if linecheck in inline:
                                is_new_repo = False
                                break
                    if is_new_repo == True:
                        new_repo_list.append(linecheck)
    print("## IMPORTANT: List of new repo in this baseline: \n" + "\n".join(new_repo_list))
    print("######### END: AC-IMAGE-REPOS-CHECK #########\n===>  ACTION: RUN THIS JOB IF THERE IS NEW REPO INTRODUCED:\nhttps://t2k-jenkins.elektrobit.com/job/BRITT/job/Conti-QNX-Mirror-Update/\n")
    l_output += '\n'+ "## IMPORTANT: List of new repo in this baseline: \n" + "\n".join(new_repo_list)
    l_output += '\n'+ "######### END: AC-IMAGE-REPOS-CHECK #########\n===>  ACTION: RUN THIS JOB IF THERE IS NEW REPO INTRODUCED:\nhttps://t2k-jenkins.elektrobit.com/job/BRITT/job/Conti-QNX-Mirror-Update/\n"
    return l_output

def meta_sw_check(ws):
    patch_list = list()
    repo_list = list()
    new_repo_list = list()
    patch_dict = dict()
    patch_dict_check = dict()
    l_output = ""
    fetching_workspace(ws)
    # Clean up meta-sw-t2k/recipes-artifact-extended before proceeding
    for file in glob.glob(os.path.join(ws, 'meta-sw-t2k', 'recipes-artifact-extended', '*.*'), recursive=True):
        os.remove(file)
    patch_check_ws = os.path.join(ws,'patch_check_ws')
    if not os.path.exists(patch_check_ws):
        os.mkdir(patch_check_ws)
    if os.path.exists(os.path.join(patch_check_ws,'patches')):
        shutil.rmtree(os.path.join(patch_check_ws,'patches'))
    os.mkdir(os.path.join(patch_check_ws,'patches'))

    print("\n\n\n######### START: NEW-ARTIFACTORY-LIST-CHECK #########")
    l_output += '\n'+ "\n\n\n######### START: NEW-ARTIFACTORY-LIST-CHECK #########"
    
    for file in glob.glob(os.path.join(ws, 'meta-*', '**', '*.*'), recursive=True):
        # Update artifactory in meta-sw-t2k against iip baseline
        if not 'meta-sw-t2k' in file and (file.endswith('.bb') or file.endswith('.bbappend') or file.endswith('.inc')):
            composer = ""
            need2port = False
            with open(file, 'r') as infile:
                for inline in infile:
                    tmp_composer = ""
                    # Check for new artifactory url to be mirrored:
                    if re.search("SRC_URI.*https?:\/\/buic-scm-wet.contiwan.com\/artifactory.*", inline):
                        need2port = True
                        tmp_composer = re.sub("https?:\/\/buic-scm-wet.contiwan.com\/artifactory\/","",inline).split(" ")[-1].split("/")[0].replace("\"","")
                        composer += re.sub("https?:\/\/buic-scm-wet.contiwan.com\/artifactory\/", configs["server"]["artifactory"], inline).replace(tmp_composer,"")

                    # Check for new gerrit/github repo introduced by iip/qualcomm:
                    for server in conti_server_list:
                        if re.search(server + ".*protocol=ssh.*",inline):
                            repo_list.append(re.sub("git.*:\/\/","",inline.split(";")[0].strip().replace("\"","").split("=")[-1].strip()).replace("git@","").replace(".git",""))

                if need2port == True:
                    with open(os.path.join(ws, 'meta-sw-t2k', 'recipes-artifact-extended', os.path.basename(file)+"append"),'w+') as outfile:
                        print("## IMPORTANT: New artifactory update found: " + composer)
                        l_output += '\n'+ "## IMPORTANT: New artifactory update found: " + composer
                        outfile.write(composer)

        # Check and update patches:
        if 'meta-sw-t2k' in file and (file.endswith('.bb') or file.endswith('.bbappend') or file.endswith('.inc')):
            patch_list = []
            has_patch = False
            filename_plain = file.split('/')[-1].replace('.bbappend','').replace('.inc','').replace('.bb','').strip()
            with open(file, 'r') as infile:
                for inline in infile:
                    if re.search("file:\/\/.*.patch", inline):
                        inline = re.sub("SRC_URI.*=","",re.sub("file:\/\/","", inline).split('/')[-1].strip().replace("\"","").replace("\\","").replace("\n","")).split(";")[0].strip()
                        patch_list.append(filename_plain+'_'+inline.strip())
                        has_patch = True
                        for pfile in glob.glob(os.path.join(ws, 'meta-sw-t2k', '**', inline.strip()), recursive=True):
                            shutil.copyfile(pfile,os.path.join(patch_check_ws,'patches',filename_plain+'_'+inline.strip()))
            if has_patch == True:
                patch_dict[file] = patch_list
    print("######### END: NEW-ARTIFACTORY-LIST-CHECK #########\n===> ACTION: RUN THIS JOB WITH NEW COMMIT FROM QNX-MANIFEST:\nhttps://t2k-jenkins.elektrobit.com/job/BRITT/job/Conti-Artifactory-Mirror-Update\n")
    l_output += '\n'+ "######### END: NEW-ARTIFACTORY-LIST-CHECK #########\n===> ACTION: RUN THIS JOB WITH NEW COMMIT FROM QNX-MANIFEST:\nhttps://t2k-jenkins.elektrobit.com/job/BRITT/job/Conti-Artifactory-Mirror-Update\n"

    repo_list = set(repo_list)
    is_new_repo = True
    for repo in repo_list:
        is_new_repo = True
        with open(gerrit_list_links, 'r') as infile:
            for inline in infile:
                if repo.split("/")[-1] in inline:
                    is_new_repo = False
                    break
        if is_new_repo == True:
            new_repo_list.append(repo)
    print("\n\n\n######### START: NEW-REPO-LIST-CHECK #########")
    print("## IMPORTANT: List of new repo in this baseline: \n" + "\n".join(new_repo_list))
    print("######### END: NEW-REPO-LIST-CHECK #########\n===> ACTION: RUN THIS JOB IF THERE IS NEW REPO INTRODUCED:\nhttps://t2k-jenkins.elektrobit.com/job/BRITT/job/Conti-QNX-Mirror-Update/\n")
    l_output += '\n'+ "\n\n\n######### START: NEW-REPO-LIST-CHECK #########"
    l_output += '\n'+ "## IMPORTANT:List of new repo in this baseline: \n" + "\n".join(new_repo_list)
    l_output += '\n'+ "######### END: NEW-REPO-LIST-CHECK #########\n===> ACTION: RUN THIS JOB IF THERE IS NEW REPO INTRODUCED:\nhttps://t2k-jenkins.elektrobit.com/job/BRITT/job/Conti-QNX-Mirror-Update/\n"


    print("\n\n\n######### START: META-SW-T2K-CHECK #########\n")
    l_output += '\n'+ "\n\n\n######### START: META-SW-T2K-CHECK #########\n"
    for pt in patch_dict.items():
        print(pt)
        for file in glob.glob(os.path.join(ws, 'meta-*', '**', os.path.basename(pt[0]).replace(".bbappend",".bb*")), recursive=True):
            repo_url = ""
            revision = ""
            repo_found = False
            cmd = []
            if 'meta-sw-t2k' in file:
                with open(file, 'r') as infile:
                    for inline in infile:
                        if ("branch="+configs["project"]["baseline_branch"] in inline) and ("ebsectools" not in inline):
                            print("""\n\n## IMPORTANT-PATCH-CHECK ##\nUnnecessary Patches In Used: {PATCHES} \n \
                                The actual code from the patches can be commited directly to {REPO}""".format(PATCHES=",".join(pt[1]),REPO=re.sub("git:\/\/","",inline).replace(";"," - ")))

                            l_output += '\n'+ """\n\n## IMPORTANT-PATCH-CHECK ##\nUnnecessary Patches In Used: {PATCHES} \n \
                                The actual code from the patches can be commited directly to {REPO}""".format(PATCHES=",".join(pt[1]),REPO=re.sub("git:\/\/","",inline).replace(";"," - "))
            else:
                with open(file, 'r') as infile:
                    for inline in infile:
                        for server in conti_server_list:
                            if re.search(server + ".*protocol=ssh.*",inline) or re.search(server + ".*OIP_GIT_PARAMS.*",inline):
                                print(inline)
                                l_output += '\n'+ inline
                                if "29418" in inline:
                                    repo_url = re.sub("git.*//","ssh://",re.sub("SRC_URI.*\s?=\s?\"","",inline).strip().strip("\"").split(";")[0].replace(".git","").replace("git@","").replace("/${OIP_GIT_PARAMS}",""))
                                else:
                                    repo_url = re.sub("git.*//","ssh://",re.sub("SRC_URI.*\s?=\s?\"","",inline).strip().strip("\"").split(";")[0].replace(".git","").replace("/${OIP_GIT_PARAMS}",""))
                                print(repo_url)
                                l_output += '\n'+ repo_url
                                repo_branch = re.sub("SRC_URI.*branch=","",inline).strip().strip("\"").split(";")[0].replace("\\","").strip()
                                if not os.path.exists(os.path.join(patch_check_ws,os.path.basename(repo_url))):
                                    os.chdir(patch_check_ws)
                                    execute(['git','clone',repo_url,os.path.basename(repo_url)])
                                    os.chdir(ws)
                                else:
                                    os.chdir(os.path.join(patch_check_ws,os.path.basename(repo_url)))
                                    try:
                                        execute(['git','reset','--hard','HEAD'])
                                        execute(['git','clean','-dfx'])
                                        execute(['git','remote','update'])
                                    except:
                                        pass
                                    os.chdir(ws)
                                repo_found = True
                                if revision != "":
                                    os.chdir(os.path.join(patch_check_ws,os.path.basename(repo_url)))
                                    print(os.path.join(patch_check_ws,os.path.basename(repo_url)))
                                    l_output += '\n'+ os.path.join(patch_check_ws,os.path.basename(repo_url))
                                    try:
                                        execute(['git','checkout',revision])
                                    except:
                                        pass
                                    os.chdir(ws)

                        if re.search("SRCREV.*=.*",inline) and not re.search("SRCREV_qnx7phoenix1",inline) and repo_url != "" and not re.search("SRCREV_FORMAT",inline):
                            revision = re.sub("SRCREV.*=","",inline).replace("\"","").strip()
                            os.chdir(os.path.join(patch_check_ws,os.path.basename(repo_url)))
                            print(os.path.join(patch_check_ws,os.path.basename(repo_url)))
                            l_output += '\n'+ os.path.join(patch_check_ws,os.path.basename(repo_url))
                            try:
                                execute(['git','reset','--hard','HEAD'])
                                execute(['git','clean','-dfx'])
                                execute(['git','checkout',revision])
                            except:
                                pass
                            os.chdir(ws)
                if repo_found == False:
                    for incfile in glob.glob(os.path.join(os.path.dirname(file), '**', "*.inc"), recursive=True):
                        with open(incfile,'r') as incinfile:
                            for incline in incinfile:
                                for server in conti_server_list:
                                    if re.search(server + ".*protocol=ssh.*",incline) or re.search(server + ".*OIP_GIT_PARAMS.*",incline):
                                        print(incline)
                                        l_output += '\n'+ incline
                                        if "29418" in incline:
                                            repo_url = re.sub("git.*//","ssh://",re.sub("SRC_URI.*\s?=\s?\"","",incline).strip().strip("\"").split(";")[0].replace(".git","").replace("git@","").replace("/${OIP_GIT_PARAMS}",""))
                                        else:
                                            repo_url = re.sub("git.*//","ssh://",re.sub("SRC_URI.*\s?=\s?\"","",incline).strip().strip("\"").split(";")[0].replace(".git","").replace("/${OIP_GIT_PARAMS}",""))
                                        print(repo_url)
                                        l_output += '\n'+ repo_url
                                        repo_branch = re.sub("SRC_URI.*branch=","",incline).strip().strip("\"").split(";")[0].replace("\\","").strip()
                                        if not os.path.exists(os.path.join(patch_check_ws,os.path.basename(repo_url))):
                                            os.chdir(patch_check_ws)
                                            execute(['git','clone',repo_url,os.path.basename(repo_url)])
                                            os.chdir(ws)
                                        else:
                                            os.chdir(os.path.join(patch_check_ws,os.path.basename(repo_url)))
                                            try:
                                                execute(['git','reset','--hard','HEAD'])
                                                execute(['git','clean','-dfx'])
                                                execute(['git','remote','update'])
                                            except:
                                                pass
                                            os.chdir(ws)
                                        if revision != "":
                                            os.chdir(os.path.join(patch_check_ws,os.path.basename(repo_url)))
                                            print(os.path.join(patch_check_ws,os.path.basename(repo_url)))
                                            l_output += '\n'+ os.path.join(patch_check_ws,os.path.basename(repo_url))
                                            try:
                                                execute(['git','checkout',revision])
                                            except:
                                                pass
                                            os.chdir(ws)

                                if re.search("SRCREV.*=.*",incline) and not re.search("SRCREV_qnx7phoenix1",inline) and not re.search("SRCREV_FORMAT",incline):
                                    revision = re.sub("SRCREV.*=","",incline).replace("\"","").strip()
                                    os.chdir(os.path.join(patch_check_ws,os.path.basename(repo_url)))
                                    try:
                                        execute(['git','reset','--hard','HEAD'])
                                        execute(['git','clean','-dfx'])
                                        execute(['git','checkout',revision])
                                    except:
                                        pass
                                    os.chdir(ws)
            
            if os.path.exists(os.path.join(patch_check_ws,os.path.basename(repo_url))) and repo_url != "":
                patch_dict_check[os.path.basename(repo_url)] = pt[1]
    for ptc in patch_dict_check.items():
        os.chdir(os.path.join(patch_check_ws,ptc[0]))
        try:
            execute(['git','reset','--hard','HEAD'])
            execute(['git','clean','-dfx'])
        except:
            pass
        for p in ptc[1]:
            print("\n\n\n## IMPORTANT: Applying patch {} for {}".format(p,os.path.join(patch_check_ws,ptc[0])))
            l_output += '\n'+ "\n\n\n## IMPORTANT:Applying patch {} for {}".format(p,os.path.join(patch_check_ws,ptc[0]))
            try:
                execute("git apply -p1 < "+os.path.join(patch_check_ws,'patches',p), shell=True, log=True)
            except:
                print("FAILED TO APPLY: {} TO {}".format(p,os.path.join(patch_check_ws,ptc[0])))
                l_output += '\n'+ "FAILED TO APPLY: {} TO {}".format(p,os.path.join(patch_check_ws,ptc[0]))
        os.chdir(ws)

    print("######### END: META-SW-T2K-CHECK #########\n")
    l_output += '\n'+ "######### END: META-SW-T2K-CHECK #########\n"
    return l_output
if __name__ == "__main__":
    push_commit = True
    log_output = "LOG RUN FOR "+currtime
    if '--dry-run' in sys.argv or '-d' in sys.argv:
        push_commit = False
    # Manifest change for baseline switch
    commit_id = iip2t2k_manifest_porter(configs["iip"]["current_qnx_baseline"], configs["iip"]["next_qnx_baseline"], push_commit=push_commit)
    
    # Trigger job to update new artifacts to artifactory: https://t2k-jenkins.elektrobit.com/job/BRITT/job/Conti-Artifactory-Mirror-Update/
    # Project's software change:
    log_output += meta_sw_check(configs["project"]["workspace"])
    
    # Checking for ac-image from iip to mirror to t2k
    log_output += detect_ac_img_new_repo(acrelease)

    with open(log_file_path,"w+") as lf:
        lf.write(log_output)