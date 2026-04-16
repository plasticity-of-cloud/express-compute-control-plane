# AWS EKS Auth Service Proxy - Documentation Index

This directory contains comprehensive documentation for the AWS EKS Auth Service Proxy codebase, generated for AI assistant navigation and understanding.

## How to Use This Documentation

This index serves as the primary entry point for AI assistants to understand and navigate the codebase effectively. Each file contains specific aspects of the system with rich metadata to help determine relevance for different types of questions.

## Documentation Files

| File | Purpose | Use When |
|------|---------|----------|
| `codebase_info.md` | Basic codebase statistics and structure | Getting overview of project size and organization |
| `architecture.md` | System architecture and design patterns | Understanding overall system design and component relationships |
| `components.md` | Major components and responsibilities | Finding specific components or understanding component interactions |
| `interfaces.md` | APIs, endpoints, and integration points | Working with REST APIs, webhooks, or external integrations |
| `data_models.md` | Data structures and models | Understanding request/response formats or data flow |
| `workflows.md` | Key processes and business logic | Understanding authentication flows or operational processes |
| `dependencies.md` | External dependencies and usage | Understanding technology stack or dependency issues |
| `review_notes.md` | Documentation quality assessment | Identifying gaps or areas needing attention |

## Quick Navigation Guide

### For Development Tasks
- **Adding new features**: Start with `architecture.md` → `components.md` → `interfaces.md`
- **Bug fixes**: Check `workflows.md` → `components.md` → specific component details
- **API changes**: Review `interfaces.md` → `data_models.md`
- **Deployment issues**: Check `dependencies.md` → `architecture.md`

### For Understanding the System
- **New to codebase**: `codebase_info.md` → `architecture.md` → `components.md`
- **Authentication flow**: `workflows.md` → `interfaces.md`
- **Kubernetes integration**: `components.md` → `interfaces.md`
- **AWS integration**: `workflows.md` → `dependencies.md`

## Key System Concepts

This is a **multi-module Quarkus application** that provides EKS Pod Identity authentication services:

- **eks-auth-proxy**: Main authentication service
- **eks-d-auth-cli**: CLI tool for managing pod identity associations
- **eks-pod-identity-webhook**: Kubernetes admission webhook
- **eks-pod-identity-crd**: Custom Resource Definitions

The system enables AWS credential assumption for Kubernetes service accounts, supporting both real EKS clusters and local development environments.
