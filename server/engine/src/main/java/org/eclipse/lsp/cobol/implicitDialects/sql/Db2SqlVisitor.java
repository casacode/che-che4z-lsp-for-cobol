/*
 * Copyright (c) 2023 Broadcom.
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
package org.eclipse.lsp.cobol.implicitDialects.sql;

import com.google.common.collect.ImmutableList;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.lsp.cobol.AntlrRangeUtils;
import org.eclipse.lsp.cobol.common.copybook.CopybookService;
import org.eclipse.lsp.cobol.common.dialects.CobolDialect;
import org.eclipse.lsp.cobol.common.dialects.DialectProcessingContext;
import org.eclipse.lsp.cobol.common.error.SyntaxError;
import org.eclipse.lsp.cobol.common.message.MessageService;
import org.eclipse.lsp.cobol.common.model.Locality;
import org.eclipse.lsp.cobol.common.model.tree.Node;
import org.eclipse.lsp.cobol.common.model.tree.variable.*;
import org.eclipse.lsp.cobol.core.visitor.VisitorHelper;
import org.eclipse.lsp.cobol.implicitDialects.sql.node.*;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.eclipse.lsp.cobol.AntlrRangeUtils.constructRange;

/**
 * This visitor analyzes the parser tree for DB2 SQL and returns its semantic context as a syntax
 * tree
 */
@Slf4j
@AllArgsConstructor
class Db2SqlVisitor extends Db2SqlParserBaseVisitor<List<Node>> {

    private final DialectProcessingContext context;
    private final MessageService messageService;
    private final CopybookService copybookService;
    private static final Pattern DOUBLE_DASH_SQL_COMMENT =
            Pattern.compile("--\\s[^\\r\\n]*", Pattern.MULTILINE);

    @Getter
    private final List<SyntaxError> errors = new LinkedList<>();

    @Override
    public List<Node> visitExecRule(Db2SqlParser.ExecRuleContext ctx) {
        addReplacementContext(ctx);
        return super.visitExecRule(ctx);
    }

    @Override
    public List<Node> visitResult_set_locator_variable(Db2SqlParser.Result_set_locator_variableContext ctx) {
        return createHostVariableDefinitionNode(ctx, ctx.dbs_level_01(), ctx.entry_name());
    }

    @Override
    public List<Node> visitTableLocators_variable(Db2SqlParser.TableLocators_variableContext ctx) {
        return createHostVariableDefinitionNode(ctx, ctx.dbs_host_var_levels(), ctx.entry_name());
    }

    @Override
    public List<Node> visitBinary_host_variable_array(Db2SqlParser.Binary_host_variable_arrayContext ctx) {
        return createHostVariableDefinitionNode(ctx, ctx.dbs_host_var_levels(), ctx.entry_name());
    }

    @Override
    public List<Node> visitRowid_host_variables(Db2SqlParser.Rowid_host_variablesContext ctx) {
        return createHostVariableDefinitionNode(ctx, ctx.dbs_host_var_levels(), ctx.entry_name());
    }

    @Override
    public List<Node> visitRowid_host_variables_arrays(Db2SqlParser.Rowid_host_variables_arraysContext ctx) {
        return createHostVariableDefinitionNode(ctx, ctx.dbs_host_var_levels_arrays(), ctx.entry_name());
    }

    @Override
    public List<Node> visitLob_xml_host_variables(Db2SqlParser.Lob_xml_host_variablesContext ctx) {
        List<Node> hostVariableDefinitionNode = createHostVariableDefinitionNode(ctx, ctx.dbs_host_var_levels(), ctx.entry_name());
        VariableDefinitionNode variableDefinitionNode = (VariableDefinitionNode) hostVariableDefinitionNode.get(0);
        int generatedVariableLevel = 49;

        if (ctx.xml_lobNO_size() != null) {
            addXmlLobNodes(variableDefinitionNode, generatedVariableLevel);
        } else if (ctx.lobWithSize() != null) {
            addLobWithSizeNodes(variableDefinitionNode, generatedVariableLevel, lobSize(ctx.lobWithSize()));
        }

        return hostVariableDefinitionNode;
    }

    private void addXmlLobNodes(VariableDefinitionNode variableDefinitionNode, int generatedVariableLevel) {
        String[] suffixes = {"-NAME-LENGTH", "-DATA-LENGTH", "-FILE-OPTION", "-NAME"};
        String[] formats = {"S9(9) COMP-5", "S9(9) COMP-5", "S9(9) COMP-5", "X(255)"};
        UsageFormat[] usages = {UsageFormat.BINARY, UsageFormat.BINARY, UsageFormat.BINARY, UsageFormat.DISPLAY};

        for (int i = 0; i < suffixes.length; i++) {
            VariableNode variableNode = createVariableNode(variableDefinitionNode, generatedVariableLevel, suffixes[i], formats[i], usages[i]);
            variableDefinitionNode.addChild(variableNode);
            variableNode.addChild(new VariableDefinitionNameNode(variableDefinitionNode.getVariableName().getLocality(), variableNode.getName()));
        }
    }

    private void addLobWithSizeNodes(VariableDefinitionNode variableDefinitionNode, int generatedVariableLevel, String size) {
        String[] suffixes = {"-LENGTH", "-DATA"};
        String[] formats = {"S9(4)", "X(" + size + ")"};
        UsageFormat[] usages = {UsageFormat.BINARY, UsageFormat.DISPLAY};

        for (int i = 0; i < suffixes.length; i++) {
            VariableNode variableNode = createVariableNode(variableDefinitionNode, generatedVariableLevel, suffixes[i], formats[i], usages[i]);
            variableNode.getChildren().add(new VariableDefinitionNameNode(variableDefinitionNode.getVariableName().getLocality(), variableNode.getName()));
            variableDefinitionNode.addChild(variableNode);
        }
    }

    private VariableNode createVariableNode(VariableDefinitionNode variableDefinitionNode, int generatedVariableLevel, String suffix, String format, UsageFormat usage) {
        return new ElementaryItemNode(
                variableDefinitionNode.getLevelLocality(),
                generatedVariableLevel,
                variableDefinitionNode.getVariableName().getName() + suffix,
                false,
                format,
                "",
                usage,
                false,
                false,
                false
        );
    }

    @Override
    public List<Node> visitLob_host_variables(Db2SqlParser.Lob_host_variablesContext ctx) {
        List<Node> hostVariableDefinitionNode = createHostVariableDefinitionNode(ctx, ctx.dbs_integer(), ctx.entry_name());
        if (ctx.lobWithSize() != null) {
            generateVarbinVariables((VariableDefinitionNode) hostVariableDefinitionNode.get(0),
                    lobSize(ctx.lobWithSize()), ctx);
        }
        return hostVariableDefinitionNode;
    }

    @Override
    public List<Node> visitLob_host_variables_arrays(Db2SqlParser.Lob_host_variables_arraysContext ctx) {
        List<Node> hostVariableDefinitionNode = createHostVariableDefinitionNode(ctx, ctx.dbs_host_var_levels_arrays(), ctx.entry_name());
        if (ctx.lobWithSize() != null) {
            generateVarbinVariables((VariableDefinitionNode) hostVariableDefinitionNode.get(0),
                    lobSize(ctx.lobWithSize()), ctx);
        }
        return hostVariableDefinitionNode;
    }

    private List<Node> createHostVariableDefinitionNode(ParserRuleContext ctx, ParserRuleContext levelCtx, ParserRuleContext nameCtx) {
        addReplacementContext(ctx);
        Locality statementLocality = getLocality(this.context.getExtendedDocument().mapLocation(constructRange(ctx)));

        Db2WorkingAndLinkageSectionNode semanticsNode = new Db2WorkingAndLinkageSectionNode(statementLocality);

        VariableDefinitionNode variableDefinitionNode = VariableDefinitionNode.builder()
                .level(Integer.parseInt(levelCtx.getText()))
                .levelLocality(getLocality(this.context.getExtendedDocument().mapLocation(constructRange(levelCtx))))
                .statementLocality(statementLocality)
                .variableNameAndLocality(new VariableNameAndLocality(
                        VisitorHelper.getName(nameCtx),
                        getLocality(this.context.getExtendedDocument().mapLocation(constructRange(nameCtx)))))
                .usageClauses(ImmutableList.of(UsageFormat.DISPLAY))
                .build();

        variableDefinitionNode.addChild(semanticsNode);
        return ImmutableList.of(variableDefinitionNode);
    }

    @Override
    public List<Node> visitBinary_host_variable(Db2SqlParser.Binary_host_variableContext ctx) {
        addReplacementContext(ctx);
        Locality statementLocality = getLocality(this.context.getExtendedDocument().mapLocation(constructRange(ctx)));

        Db2WorkingAndLinkageSectionNode semanticsNode = new Db2WorkingAndLinkageSectionNode(statementLocality);

        VariableDefinitionNode variableDefinitionNode =
                VariableDefinitionNode.builder()
                        .level(Integer.parseInt(ctx.dbs_level_01().getText()))
                        .levelLocality(
                                getLocality(
                                        this.context.getExtendedDocument().mapLocation(constructRange(ctx.entry_name()))))
                        .statementLocality(statementLocality)
                        .variableNameAndLocality(
                                new VariableNameAndLocality(
                                        VisitorHelper.getName(ctx.entry_name()),
                                        getLocality(
                                                this.context
                                                        .getExtendedDocument()
                                                        .mapLocation(constructRange(ctx.entry_name())))))
                        .usageClauses(ImmutableList.of(UsageFormat.DISPLAY))
                        .build();
        if (ctx.binary_host_variable_type().VARBINARY() != null) {
            generateVarbinVariables(variableDefinitionNode, ctx.binary_host_variable_type().binary_host_variable_varbinary_size().getText(), ctx);
        }
        variableDefinitionNode.addChild(semanticsNode);
        return ImmutableList.of(variableDefinitionNode);
    }

    private void generateVarbinVariables(VariableDefinitionNode variableDefinitionNode, String len, ParserRuleContext ctx) {
        String suffux1 = "";
        String suffix2 = "";
        String picClause = "X(" + len + ")";
        switch (ctx.getClass().getSimpleName()) {
            case "Lob_host_variablesContext":
                if (((Db2SqlParser.Lob_host_variablesContext) ctx).lobWithSize().DBCLOB() != null) {
                    picClause = "G(" + len + ")";
                }
            case "Lob_xml_host_variablesContext":
            case "Lob_host_variables_arraysContext":
                suffux1 = "-LENGTH";
                suffix2 = "-DATA";
                break;
            case "Binary_host_variableContext":
                suffux1 = "-LEN";
                suffix2 = "-TEXT";
                break;
            default:
        }
        int generatedVariableLevel = 49;
        VariableNode variableLenNode = new ElementaryItemNode(variableDefinitionNode.getLevelLocality(),
                generatedVariableLevel,
                variableDefinitionNode.getVariableName().getName() + suffux1,
                false, "S9(4)", "",
                UsageFormat.BINARY, false, false, false);

        VariableNode variableTextNode = new ElementaryItemNode(variableDefinitionNode.getLevelLocality(),
                generatedVariableLevel,
                variableDefinitionNode.getVariableName().getName() + suffix2,
                false, picClause, "",
                UsageFormat.UNDEFINED, false, false, false);

        variableLenNode.getChildren().add(new VariableDefinitionNameNode(
                variableDefinitionNode.getVariableName().getLocality(), variableLenNode.getName()));
        variableTextNode.getChildren().add(new VariableDefinitionNameNode(
                variableDefinitionNode.getVariableName().getLocality(), variableTextNode.getName()));

        variableDefinitionNode.addChild(variableLenNode);
        variableDefinitionNode.addChild(variableTextNode);
    }

    private Locality getLocality(Location location) {
        Locality.LocalityBuilder builder =
                Locality.builder().uri(location.getUri()).range(location.getRange());
        String docUri = context.getExtendedDocument().getUri();
        if (!docUri.equals(location.getUri())) {
            copybookService.getCopybookUsage(docUri).stream()
                    .filter(model -> model.getUri().equals(location.getUri()))
                    .findFirst()
                    .ifPresent(model -> builder.copybookId(model.getCopybookName().getDisplayName()));
        }
        return builder.build();
    }

    private void addReplacementContext(ParserRuleContext ctx) {
        getAllTerminalNodes(ctx).forEach(node ->
                context.getExtendedDocument().replace(
                        constructRange(node.getSymbol()),
                        StringUtils.repeat(CobolDialect.FILLER, node.getText().length())));
    }

    private List<TerminalNode> getAllTerminalNodes(ParserRuleContext ctx) {
        List<TerminalNode> result = new ArrayList<>();
        for (int childNodes = 0; childNodes < ctx.getChildCount(); childNodes++) {
            ParseTree child = ctx.getChild(childNodes);
            if (child instanceof TerminalNode) {
                result.add((TerminalNode) child);
            } else {
                result.addAll(getAllTerminalNodes((ParserRuleContext) child));
            }
        }
        return result;
    }

    @Override
    public List<Node> visitSqlCode(Db2SqlParser.SqlCodeContext ctx) {
        String sqlCode = preProcessSqlComment(ctx);

        List<Node> nodes = new Db2SqlExecVisitor(context, copybookService).visitStartSqlRule(parseSQL(sqlCode, ctx));
        Db2SqlVisitorHelper.adjustNodeLocations(ctx, context, nodes);
        Location location = context.getExtendedDocument().mapLocation(AntlrRangeUtils.constructRange(ctx.getParent()));
        Locality locality = Locality.builder().range(location.getRange()).uri(location.getUri()).build();

        Node sqlNode = new ExecSqlNode(locality);
        nodes.forEach(sqlNode::addChild);
        return Collections.singletonList(sqlNode);
    }

    private String preProcessSqlComment(Db2SqlParser.SqlCodeContext ctx) {
        String sqlCode = VisitorHelper.getIntervalText(ctx);
        Matcher matcher = DOUBLE_DASH_SQL_COMMENT.matcher(sqlCode);
        while (matcher.find()) {
            Position start = findPosition(sqlCode, matcher.start());
            Position end = findPosition(sqlCode, matcher.end());
            String replace = StringUtils.repeat(CobolDialect.FILLER, matcher.end() - matcher.start() - 1);
            start = Db2SqlVisitorHelper.getAdjustedStartPosition(ctx, start);
            end = Db2SqlVisitorHelper.getAdjustedEndPosition(ctx, end);
            context.getExtendedDocument().replace(new Range(start, end), replace);
        }
        sqlCode = matcher.replaceAll("");
        return sqlCode;
    }

    private static Position findPosition(String text, int pos) {
        int c = 1;
        int line = 0;
        int col = 1;
        while (c < pos) {
            if (text.charAt(c) == '\n') {
                ++line;
                col = 1;
            } else {
                ++col;
            }
            c++;
        }
        return new Position(line, col);
    }

    private Db2SqlExecParser.StartSqlRuleContext parseSQL(
            String sqlCode, Db2SqlParser.SqlCodeContext sqlCodeContext) {
        Db2SqlExecLexer lexer = new Db2SqlExecLexer(CharStreams.fromString(sqlCode));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        Db2SqlExecParser parser = new Db2SqlExecParser(tokens);
        Db2ErrorListener listener = new Db2ErrorListener(context.getProgramDocumentUri());
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
        parser.setErrorHandler(new Db2ErrorStrategy(messageService));

        Db2SqlExecParser.StartSqlRuleContext result = parser.startSqlRule();
        for (SyntaxError err : listener.getErrors()) {
            errors.add(
                    err.toBuilder()
                            .location(Db2SqlVisitorHelper.adjustLocation(err.getLocation(), sqlCodeContext))
                            .build());
        }
        return result;
    }

    @Override
    public List<Node> visitChildren(RuleNode node) {
        VisitorHelper.checkInterruption();
        return super.visitChildren(node);
    }

    @Override
    protected List<Node> defaultResult() {
        return ImmutableList.of();
    }

    @Override
    protected List<Node> aggregateResult(List<Node> aggregate, List<Node> nextResult) {
        return Stream.concat(aggregate.stream(), nextResult.stream()).collect(toList());
    }

    private String lobSize(Db2SqlParser.LobWithSizeContext ctx) {
        String sizePrefix = ctx.k_m_g() != null ? " " + ctx.k_m_g().getText() : "";
        return ctx.dbs_integer().getText() + sizePrefix;
    }

    private List<Node> addTreeNode(ParserRuleContext ctx, Function<Locality, Node> nodeConstructor) {
        Locality locality =
                VisitorHelper.buildNameRangeLocality(
                        ctx, VisitorHelper.getName(ctx), context.getProgramDocumentUri());
        Location location = context.getExtendedDocument().mapLocation(locality.getRange());

        Node node =
                nodeConstructor.apply(
                        Locality.builder().range(location.getRange()).uri(location.getUri()).build());
        visitChildren(ctx).forEach(node::addChild);
        return ImmutableList.of(node);
    }
 }

