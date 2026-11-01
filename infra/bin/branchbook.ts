#!/usr/bin/env node
/**
 * Entry point of the BranchBook CDK app. Which stacks exist, and where each is deployed, is decided by
 * lib/app.ts from the deploy-time variables, so the tests synthesise exactly the app this file does.
 */
import { App } from 'aws-cdk-lib/core';
import { buildApp } from '../lib/app';

buildApp(new App(), process.env);
