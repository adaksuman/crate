/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.analyze;

import io.crate.analyze.relations.AnalyzedRelation;

public class Analysis {

    private final ParameterContext parameterContext;
    private AnalyzedStatement analyzedStatement;
    private boolean expectsAffectedRows = false;
    private AnalyzedRelation rootRelation;

    public Analysis(ParameterContext parameterContext) {
        this.parameterContext = parameterContext;
    }

    public void analyzedStatement(AnalyzedStatement analyzedStatement) {
        this.analyzedStatement = analyzedStatement;
    }

    public AnalyzedStatement analyzedStatement() {
        return analyzedStatement;
    }

    public ParameterContext parameterContext() {
        return parameterContext;
    }

    public void expectsAffectedRows(boolean expectsAffectedRows) {
        this.expectsAffectedRows = expectsAffectedRows;
    }

    public boolean expectsAffectedRows() {
        return expectsAffectedRows;
    }

    public void rootRelation(AnalyzedRelation rootRelation) {
        this.rootRelation = rootRelation;
    }

    public AnalyzedRelation rootRelation(){
        return rootRelation;
    }
}
