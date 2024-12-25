/*
 * Copyright (c) 2021 Broadcom.
 * The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Broadcom, Inc. - initial API and implementation
 *
 */
package org.eclipse.lsp.cobol.common.model.tree;

import lombok.ToString;

import java.util.Optional;

import org.eclipse.lsp.cobol.common.model.Locality;
import org.eclipse.lsp.cobol.common.model.variables.DivisionType;

/** The class represents procedure division in COBOL. */
@ToString(callSuper = true)
public class ProcedureDivisionNode extends DivisionNode {
  public ProcedureDivisionNode(Locality location, Optional<Locality> clauses) {
    super(location, DivisionType.PROCEDURE_DIVISION);
    clausesLocation = clauses.orElse(location);
  }

  public boolean hasReturningClause = false;
  public final Locality clausesLocation;
}
