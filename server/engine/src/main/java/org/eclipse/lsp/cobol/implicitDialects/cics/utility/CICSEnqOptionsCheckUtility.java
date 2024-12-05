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
package org.eclipse.lsp.cobol.implicitDialects.cics.utility;

        import org.antlr.v4.runtime.ParserRuleContext;

        import org.eclipse.lsp.cobol.common.dialects.DialectProcessingContext;
        import org.eclipse.lsp.cobol.common.error.ErrorSeverity;
        import org.eclipse.lsp.cobol.common.error.SyntaxError;
        import org.eclipse.lsp.cobol.implicitDialects.cics.CICSLexer;
        import org.eclipse.lsp.cobol.implicitDialects.cics.CICSParser;

        import java.util.HashMap;
        import java.util.List;
        import java.util.Map;

        import static org.eclipse.lsp.cobol.implicitDialects.cics.CICSParser.RULE_cics_enq;
        import static org.eclipse.lsp.cobol.implicitDialects.cics.CICSParser.RULE_cics_enq_opts;

/** Checks CICS ENQ rules for required and invalid options */
public class CICSEnqOptionsCheckUtility extends CICSOptionsCheckBaseUtility {

    public static final int RULE_INDEX = RULE_cics_enq;

    private static final Map<Integer, ErrorSeverity> DUPLICATE_CHECK_OPTIONS =
            new HashMap<Integer, ErrorSeverity>() {
                {
                    put(CICSLexer.ENQ, ErrorSeverity.ERROR);
                    put(CICSLexer.RESOURCE, ErrorSeverity.ERROR);
                    put(CICSLexer.LENGTH, ErrorSeverity.ERROR);
                    put(CICSLexer.UOW, ErrorSeverity.WARNING);
                    put(CICSLexer.MAXLIFETIME, ErrorSeverity.ERROR);
                    put(CICSLexer.TASK, ErrorSeverity.WARNING);
                    put(CICSLexer.NOSUSPEND, ErrorSeverity.WARNING);
                }
            };

    public CICSEnqOptionsCheckUtility(
            DialectProcessingContext context, List<SyntaxError> errors) {
        super(context, errors, DUPLICATE_CHECK_OPTIONS);
    }

    /**
     * Entrypoint to check CICS ENQ rule options
     *
     * @param ctx ParserRuleContext subclass containing options
     * @param <E> A subclass of ParserRuleContext
     */
    public <E extends ParserRuleContext> void checkOptions(E ctx) {
        if (ctx.getRuleIndex() == RULE_cics_enq_opts)
            checkEnq((CICSParser.Cics_enq_optsContext) ctx);

        checkDuplicates(ctx);
    }
    private void checkEnq(CICSParser.Cics_enq_optsContext ctx) {
        checkHasMandatoryOptions(ctx.RESOURCE(), ctx, "RESOURCE");
        checkHasMutuallyExclusiveOptions("UOW or MAXLIFETIME or TASK", ctx.UOW(), ctx.MAXLIFETIME(), ctx.TASK());
    }
}
