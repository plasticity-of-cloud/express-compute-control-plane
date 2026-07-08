*** Settings ***
Documentation    Tests for list-clusters and describe-cluster.

Library          ../libraries/MockServerLibrary.py
Resource         ../resources/common.resource

Suite Setup      Start Suite
Suite Teardown   Stop Mock Server


*** Variables ***
${PORT}    ${EMPTY}


*** Keywords ***
Start Suite
    ${p}=    Start Mock Server
    Set Suite Variable    ${PORT}    ${p}


*** Test Cases ***

List Clusters Returns Output
    [Setup]    Reset Mock Server
    Set Response    GET    /clusters    200
    ...    {"clusters":[{"clusterName":"prod","managed":true,"state":"ready"},{"clusterName":"staging","managed":false}]}
    ${r}=    Run CLI With Mock    ${PORT}    list-clusters
    Should Succeed    ${r}

List Clusters Empty Returns Success
    [Setup]    Reset Mock Server
    Set Response    GET    /clusters    200    {"clusters":[]}
    ${r}=    Run CLI With Mock    ${PORT}    list-clusters
    Should Succeed    ${r}

Describe Cluster Shows Details
    [Setup]    Reset Mock Server
    Set Response    GET    /clusters/prod    200
    ...    {"clusterName":"prod","issuer":"https://prod.example.com","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-02T00:00:00Z"}
    ${r}=    Run CLI With Mock    ${PORT}    describe-cluster    prod
    Should Succeed    ${r}
    Stdout Should Contain    ${r}    prod

Describe Cluster Not Found Exits Nonzero
    [Setup]    Reset Mock Server
    Set Response    GET    /clusters/ghost    404
    ...    {"__type":"NotFoundException","message":"Cluster not found: ghost"}
    ${r}=    Run CLI With Mock    ${PORT}    describe-cluster    ghost
    Should Fail    ${r}
    Stderr Should Contain    ${r}    ghost
