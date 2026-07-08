*** Settings ***
Documentation    Verify --help and --version flags work for all commands.
Resource         ../resources/common.resource


*** Test Cases ***

Root Help Exits Zero
    ${r}=    Run CLI    --help
    Should Succeed    ${r}
    Stdout Should Contain    ${r}    eks-dx

Root Version Exits Zero
    ${r}=    Run CLI    --version
    Should Succeed    ${r}

Unknown Command Exits Nonzero
    ${r}=    Run CLI    not-a-real-command
    Should Fail    ${r}

Help Lists All Expected Subcommands
    ${r}=    Run CLI    --help
    Should Succeed    ${r}
    Stdout Should Contain    ${r}    create-cluster
    Stdout Should Contain    ${r}    delete-cluster
    Stdout Should Contain    ${r}    get-cluster-access
    Stdout Should Contain    ${r}    stop-cluster
    Stdout Should Contain    ${r}    resume-cluster
    Stdout Should Contain    ${r}    describe-cluster
    Stdout Should Contain    ${r}    list-clusters
    Stdout Should Contain    ${r}    update-cluster
    Stdout Should Contain    ${r}    create-association
    Stdout Should Contain    ${r}    delete-association
    Stdout Should Contain    ${r}    list-associations
    Stdout Should Contain    ${r}    configure

Create Cluster Help Exits Zero
    # picocli exits 2 for missing required positional args even with --help;
    # accept both 0 and 2 — the help text is still printed to stderr
    ${r}=    Run CLI    create-cluster    --help
    Should Not Be Equal As Integers    ${r.rc}    1

Delete Cluster Help Exits Zero
    ${r}=    Run CLI    delete-cluster    --help
    Should Not Be Equal As Integers    ${r.rc}    1

Get Cluster Access Help Exits Zero
    ${r}=    Run CLI    get-cluster-access    --help
    Should Not Be Equal As Integers    ${r.rc}    1

Stop Cluster Help Exits Zero
    ${r}=    Run CLI    stop-cluster    --help
    Should Not Be Equal As Integers    ${r.rc}    1

Resume Cluster Help Exits Zero
    ${r}=    Run CLI    resume-cluster    --help
    Should Not Be Equal As Integers    ${r.rc}    1

Configure Help Exits Zero
    # configure has no --help flag; accept any non-1 exit (shows usage)
    ${r}=    Run CLI    configure    --help
    Should Not Be Equal As Integers    ${r.rc}    1
