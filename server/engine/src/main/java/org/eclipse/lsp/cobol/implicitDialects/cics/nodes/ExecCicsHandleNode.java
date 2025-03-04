/*
 * Copyright (c) 2024 Broadcom.
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

package org.eclipse.lsp.cobol.implicitDialects.cics.nodes;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.eclipse.lsp.cobol.common.model.Locality;
import org.eclipse.lsp.cobol.common.model.NodeType;
import org.eclipse.lsp.cobol.common.model.tree.Node;
import org.eclipse.lsp.cobol.implicitDialects.cics.CICSDialect;

/** EXEC CICS HANDLE node */
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ExecCicsHandleNode extends Node {

  /**
   * Handle Abend Type Enum
   */
  public enum HandleAbendType {
    CANCEL,
    PROGRAM,
    LABEL,
    RESET
  }

  @Getter
  private final HandleAbendType type;

  public ExecCicsHandleNode(Locality location, HandleAbendType type) {
    super(location, NodeType.STATEMENT, CICSDialect.DIALECT_NAME);
    this.type = type;
  }
}
