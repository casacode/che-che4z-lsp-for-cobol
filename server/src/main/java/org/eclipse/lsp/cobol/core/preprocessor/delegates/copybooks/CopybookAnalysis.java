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

package org.eclipse.lsp.cobol.core.preprocessor.delegates.copybooks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.ParserRuleContext;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.lsp.cobol.core.messages.MessageService;
import org.eclipse.lsp.cobol.core.model.*;
import org.eclipse.lsp.cobol.core.preprocessor.TextPreprocessor;
import org.eclipse.lsp.cobol.core.preprocessor.delegates.util.PreprocessorStringUtils;
import org.eclipse.lsp.cobol.core.semantics.NamedSubContext;
import org.eclipse.lsp.cobol.service.CopybookConfig;
import org.eclipse.lsp.cobol.service.CopybookService;
import org.eclipse.lsp.cobol.service.PredefinedCopybooks;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static java.util.UUID.randomUUID;
import static java.util.stream.Collectors.toList;
import static org.codehaus.plexus.util.StringUtils.isEmpty;
import static org.eclipse.lsp.cobol.core.model.ErrorCode.MISSING_COPYBOOK;
import static org.eclipse.lsp.cobol.core.model.ErrorSeverity.ERROR;
import static org.eclipse.lsp.cobol.core.model.ErrorSeverity.INFO;
import static org.eclipse.lsp.cobol.core.preprocessor.ProcessingConstants.*;

/**
 * This class is a framework for the copybook analysis. The actual implementations may change the
 * behavior overriding the methods.
 */
@Slf4j
abstract class CopybookAnalysis {
  protected static final int MAX_COPYBOOK_NAME_LENGTH_DEFAULT = Integer.MAX_VALUE;
  private static final String HYPHEN = "-";
  private static final String UNDERSCORE = "_";
  private static final String SYNTAX_ERROR_CHECK_COPYBOOK_NAME =
      "Syntax error by checkCopybookName: {}";

  protected final Deque<CopybookUsage> copybookStack;
  private final TextPreprocessor preprocessor;
  private final CopybookService copybookService;
  private final MessageService messageService;
  protected int maxCopybookNameLength;

  CopybookAnalysis(
      TextPreprocessor preprocessor,
      CopybookService copybookService,
      Deque<CopybookUsage> copybookStack,
      MessageService messageService,
      int maxCopybookNameLength) {
    this.preprocessor = preprocessor;
    this.copybookService = copybookService;
    this.copybookStack = copybookStack;
    this.messageService = messageService;
    this.maxCopybookNameLength = maxCopybookNameLength;
  }

  /**
   * Handle the copy statement applying the logic according to the specific implementation.
   *
   * @param context
   * @param copySource
   * @return the functions that should be applied to the preprocessor
   */
  public PreprocessorFunctor handleCopybook(
      ParserRuleContext context,
      ParserRuleContext copySource,
      CopybookConfig config,
      String documentUri) {
    CopybookMetaData metaData =
        CopybookMetaData.builder()
            .name(retrieveCopybookName(copySource))
            .context(context)
            .documentUri(documentUri)
            .copybookId(randomUUID().toString())
            .config(config)
            .nameLocality(
                PreprocessorUtils.buildLocality(copySource, documentUri, copybookStack.peek()))
            .contextLocality(
                PreprocessorUtils.buildLocality(context, documentUri, copybookStack.peek()))
            .build();

    List<SyntaxError> errors = new ArrayList<>(checkCopybookName(metaData, maxCopybookNameLength));
    return (recursiveReplaceStack, replacingClauses) -> {
      ExtendedDocument copybookDocument =
          buildExtendedDocumentForCopybook(metaData)
              .apply(recursiveReplaceStack, replacingClauses)
              .unwrap(errors::addAll);
      return stack -> {
        writeText(metaData, copybookDocument).accept(stack);
        return subContext -> {
          storeCopyStatementSemantics(metaData, copybookDocument).accept(subContext);
          return nestedMappings -> {
            collectNestedSemanticData(metaData, copybookDocument).accept(nestedMappings);
            return allErrors -> allErrors.addAll(errors);
          };
        };
      };
    };
  }

  private Consumer<PreprocessorStack> writeText(
      CopybookMetaData metaData, ExtendedDocument copybookDocument) {
    return beforeWriting()
        .andThen(writeCopybook(metaData.getCopybookId(), copybookDocument.getText()))
        .andThen(afterWriting(metaData.getContext()));
  }

  protected Consumer<NamedSubContext> storeCopyStatementSemantics(
      CopybookMetaData metaData, ExtendedDocument copybookDocument) {
    return addCopybookUsage(metaData)
        .andThen(addCopybookDefinition(metaData, copybookDocument.getUri()))
        .andThen(collectCopybookStatement(metaData))
        .andThen(addNestedCopybook(copybookDocument));
  }

  private BiFunction<
          Deque<List<Pair<String, String>>>,
          List<Pair<String, String>>,
          ResultWithErrors<ExtendedDocument>>
      buildExtendedDocumentForCopybook(CopybookMetaData metaData) {
    List<SyntaxError> errors = new ArrayList<>();
    CopybookModel model = getCopyBookContent(metaData).unwrap(errors::addAll);
    return (recursiveReplaceStack, replacingClauses) ->
        new ResultWithErrors<>(
            processCopybook(
                    recursiveReplaceStack,
                    replacingClauses,
                    metaData,
                    model.getUri(),
                    handleReplacing(
                            recursiveReplaceStack,
                            metaData,
                            preprocessor
                                .cleanUpCode(model.getUri(), model.getContent())
                                .unwrap(errors::addAll))
                        .unwrap(errors::addAll))
                .unwrap(errors::addAll),
            errors);
  }

  private Consumer<Map<String, DocumentMapping>> collectNestedSemanticData(
      CopybookMetaData metaData, ExtendedDocument copybookDocument) {
    return nestedMapping -> {
      nestedMapping.putAll(copybookDocument.getDocumentMapping());
      nestedMapping.putIfAbsent(
          metaData.getCopybookId(),
          Optional.ofNullable(nestedMapping.get(copybookDocument.getUri()))
              .orElseGet(() -> new DocumentMapping(ImmutableList.of(), ImmutableMap.of())));
    };
  }

  protected ResultWithErrors<ExtendedDocument> processCopybook(
      Deque<List<Pair<String, String>>> recursiveReplaceStmtStack,
      List<Pair<String, String>> replacingClauses,
      CopybookMetaData metaData,
      String uri,
      String content) {
    copybookStack.push(metaData.toCopybookUsage());
    final ResultWithErrors<ExtendedDocument> result =
        preprocessor.processCleanCode(
            uri,
            content,
            copybookStack,
            metaData.getConfig(),
            recursiveReplaceStmtStack,
            replacingClauses);
    copybookStack.pop();
    if (Objects.nonNull(recursiveReplaceStmtStack.peek())) recursiveReplaceStmtStack.pop();
    return result;
  }

  protected ResultWithErrors<CopybookModel> getCopyBookContent(CopybookMetaData metaData) {
    List<SyntaxError> errors = new ArrayList<>();
    if (metaData.getName().isEmpty()) return emptyModel(metaData.getName(), errors);

    if (hasRecursion(metaData.getName())) {
      errors.addAll(copybookStack.stream().map(this::reportRecursiveCopybook).collect(toList()));
      return emptyModel(metaData.getName(), errors);
    }

    CopybookModel copybook =
        copybookService.resolve(
            metaData.getName(), metaData.getDocumentUri(), metaData.getConfig());
    if (copybook.getContent() == null) {
      return emptyModel(metaData.getName(), ImmutableList.of(reportMissingCopybooks(metaData)));
    }

    return new ResultWithErrors<>(copybook, errors);
  }

  protected String retrieveCopybookName(ParserRuleContext ctx) {
    return PreprocessorStringUtils.trimQuotes(ctx.getText().toUpperCase());
  }

  protected Consumer<PreprocessorStack> beforeWriting() {
    return PreprocessorStack::pop;
  }

  protected Consumer<PreprocessorStack> afterWriting(ParserRuleContext context) {
    return it -> it.accumulateTokenShift(context);
  }

  private Consumer<NamedSubContext> collectCopybookStatement(CopybookMetaData metaData) {
    return it -> it.addStatement(metaData.getCopybookId(), metaData.getContextLocality());
  }

  protected Consumer<NamedSubContext> addCopybookUsage(CopybookMetaData metaData) {
    return copybooks ->
        copybooks.addUsage(metaData.getName(), metaData.getNameLocality().toLocation());
  }

  protected Consumer<NamedSubContext> addCopybookDefinition(CopybookMetaData metaData, String uri) {
    return copybooks -> {
      if (!(isEmpty(metaData.getName()) || isEmpty(uri) || isPredefined(uri)))
        copybooks.define(
            metaData.getName(),
            new Location(uri, new Range(new Position(0, 0), new Position(0, 0))));
    };
  }

  protected Consumer<NamedSubContext> addNestedCopybook(ExtendedDocument copybookDocument) {
    return copybooks -> copybooks.merge(copybookDocument.getCopybooks());
  }

  protected ResultWithErrors<String> handleReplacing(
      Deque<List<Pair<String, String>>> recursiveReplaceStmtStack,
      CopybookMetaData metaData,
      String text) {
    return new ResultWithErrors<>(text, ImmutableList.of());
  }

  private boolean hasRecursion(String copybookName) {
    return copybookStack.stream().map(CopybookUsage::getName).anyMatch(copybookName::equals);
  }

  private Consumer<PreprocessorStack> writeCopybook(String copybookId, String copybookContent) {
    return it ->
        it.write(CPY_ENTER_TAG + copybookId + CPY_URI_CLOSE + copybookContent + CPY_EXIT_TAG);
  }

  private SyntaxError reportMissingCopybooks(CopybookMetaData metaData) {
    SyntaxError error =
        SyntaxError.syntaxError()
            .locality(metaData.getNameLocality())
            .suggestion(
                messageService.getMessage(
                    "GrammarPreprocessorListener.errorSuggestion", metaData.getName()))
            .severity(ERROR)
            .errorCode(MISSING_COPYBOOK)
            .build();
    LOG.debug("Syntax error by reportMissingCopybooks: {}", error.toString());
    return error;
  }

  private List<SyntaxError> checkCopybookName(CopybookMetaData metaData, int maxLength) {
    List<SyntaxError> errors = new ArrayList<>();
    final String copybookName = metaData.getName();
    final Locality locality = metaData.getNameLocality();
    if (copybookName.length() > maxLength) {
      errors.add(
          addCopybookError(
              copybookName,
              maxLength,
              locality,
              INFO,
              "GrammarPreprocessorListener.copyBkOverMaxChars",
              SYNTAX_ERROR_CHECK_COPYBOOK_NAME));
    }
    // The first or last character must not be a hyphen.
    if (copybookName.startsWith(HYPHEN) || copybookName.endsWith(HYPHEN)) {
      errors.add(
          addCopybookError(
              copybookName,
              locality,
              ERROR,
              "GrammarPreprocessorListener.copyBkStartsOrEndsWithHyphen",
              SYNTAX_ERROR_CHECK_COPYBOOK_NAME));
    }

    // copybook Name can't contain _
    if (copybookName.contains(UNDERSCORE))
      errors.add(
          addCopybookError(
              copybookName,
              locality,
              ERROR,
              "GrammarPreprocessorListener.copyBkContainsUnderScore",
              SYNTAX_ERROR_CHECK_COPYBOOK_NAME));
    return errors;
  }

  protected ResultWithErrors<CopybookModel> emptyModel(
      String copybookName, List<SyntaxError> errors) {
    return new ResultWithErrors<>(new CopybookModel(copybookName, "", ""), errors);
  }

  protected SyntaxError addCopybookError(
      String copybookName,
      Locality locality,
      ErrorSeverity info,
      String messageID,
      String logMessage) {
    SyntaxError error =
        SyntaxError.syntaxError()
            .severity(info)
            .suggestion(messageService.getMessage(messageID, copybookName))
            .locality(locality)
            .build();
    LOG.debug(logMessage, error.toString());
    return error;
  }

  protected SyntaxError addCopybookError(
      String copybookName,
      int maxNameLength,
      Locality locality,
      ErrorSeverity info,
      String messageID,
      String logMessage) {
    SyntaxError error =
        SyntaxError.syntaxError()
            .severity(info)
            .suggestion(messageService.getMessage(messageID, maxNameLength, copybookName))
            .locality(locality)
            .build();
    LOG.debug(logMessage, error.toString());
    return error;
  }

  private boolean isPredefined(String uri) {
    return PredefinedCopybooks.isCopybookPredefined(uri);
  }

  private SyntaxError reportRecursiveCopybook(CopybookUsage usage) {
    return addCopybookError(
        usage.getName(),
        usage.getLocality(),
        ERROR,
        "GrammarPreprocessorListener.recursionDetected",
        "Syntax error by reportRecursiveCopybook: {}");
  }
}
