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
package org.eclipse.lsp.cobol.service;

import com.google.common.collect.ImmutableList;
import com.google.inject.Singleton;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.lsp.cobol.common.AnalysisResult;
import org.eclipse.lsp.cobol.service.utils.BuildOutlineTreeFromSyntaxTree;
import org.eclipse.lsp4j.Diagnostic;

/**
 * Provides document model management functionality
 */
@Singleton
@Slf4j
public class DocumentModelService {
  private final Map<String, CobolDocumentModel> docs = new HashMap<>();
  private final Map<String, List<Diagnostic>> diagnosticRepo = Collections.synchronizedMap(new HashMap<>());

  /**
   * Mark the document as opened and stores document text
   *
   * @param uri        - document uri
   * @param text       - document text
   * @param languageId
   */
  @Synchronized
  public void openDocument(String uri, String text, String languageId) {
    CobolDocumentModel model = docs.computeIfAbsent(uri, u -> new CobolDocumentModel(uri, text));
    Optional.ofNullable(languageId).ifPresent(model::setLanguageId);
    model.setOpened(true);
  }

  /**
   * Returns document model object
   *
   * @param uri - document uri
   * @return the document model object
   */
  @Synchronized
  public CobolDocumentModel get(String uri) {
    return docs.get(uri);
  }

  /**
   * Process analysis result and store diagnostics
   *
   * @param uri            - document uri
   * @param analysisResult - analysis result
   * @param text           - updated text
   */
  @Synchronized
  public void processAnalysisResult(String uri, AnalysisResult analysisResult, String text) {
    CobolDocumentModel document = docs.get(uri);
    if (document == null) {
      LOG.warn("Can't process analysis result of " + uri);
      return;
    }
    removeAllRelatedDiagnostics(document);
    updateDiagnosticRepo(uri, analysisResult.getDiagnostics());
    CobolDocumentModel updatedModel = new CobolDocumentModel(uri, text, analysisResult);
    updatedModel.setLanguageId(document.getLanguageId());
    updatedModel.setOutlineResult(BuildOutlineTreeFromSyntaxTree.convert(analysisResult.getRootNode(), uri));
    docs.put(uri, updatedModel);
  }

  /**
   * Mark the document as closed
   *
   * @param uri - document uri
   */
  @Synchronized
  public void closeDocument(String uri) {
    Optional.ofNullable(docs.get(uri))
            .ifPresent(
                    d -> {
                      d.setOpened(false);
                      removeAllRelatedDiagnostics(d);
                    });
  }

  /**
   * Updates the document with a new text
   * @param uri - document uri
   * @param text - document text
   */
  @Synchronized
  public void changeDocument(String uri, String text) {
    Optional.ofNullable(docs.get(uri)).ifPresent(d -> d.update(text));
  }

  /**
   * Removes document diagnostics from registry
   *
   * @param uri - document uri
   */
  @Synchronized
  public void removeDocumentDiagnostics(String uri) {
    diagnosticRepo.computeIfPresent(uri, (k, v) -> Collections.emptyList());
  }

  /**
   * Returns all opened documents
   *
   * @return a list of opened documents
   */
  @Synchronized
  public List<CobolDocumentModel> getAllOpened() {
    return docs.values().stream().filter(CobolDocumentModel::isOpened).collect(Collectors.toList());
  }

  /**
   * Returns true if document was analysed and false otherwise
   *
   * @param uri - document uri
   * @return true if document was analysed and false otherwise
   */
  @Synchronized
  public boolean isDocumentSynced(String uri) {
    return Optional.ofNullable(docs.get(uri))
            .map(CobolDocumentModel::isDocumentSynced)
            .orElse(false);
  }

  /**
   * Returns all available diagnostics for opened documents and empty diagnostics for clothed
   * documents
   *
   * @return map of diagnostics where the key is a document uri and a value is a list of diagnostics
   * for this document
   */
  @Synchronized
  public Map<String, List<Diagnostic>> getOpenedDiagnostic() {
    Map<String, List<Diagnostic>> result = new HashMap<>();
    for (Map.Entry<String, CobolDocumentModel> entry : docs.entrySet()) {
      String uri = entry.getKey();
      CobolDocumentModel document = entry.getValue();
      List<Diagnostic> diagnostics = diagnosticRepo.get(document.getUri());
      if (diagnostics != null && document.isOpened()) {
        result.put(uri, diagnostics);
      } else {
        result.put(uri, ImmutableList.of());
      }
    }

    return result;
  }

  private void removeAllRelatedDiagnostics(CobolDocumentModel documentModel) {
    AnalysisResult analysisResult = documentModel.getAnalysisResult();
    if (analysisResult == null || analysisResult.getDiagnostics() == null) {
      return;
    }
    for (Map.Entry<String, List<Diagnostic>> entry : analysisResult.getDiagnostics().entrySet()) {
      String uri = entry.getKey();
      if (!diagnosticRepo.containsKey(uri)) {
        continue;
      }
      List<Diagnostic> closedDiagnostics = entry.getValue();
      List<Diagnostic> newDiagnostics = diagnosticRepo.get(uri).stream()
              .filter(diagnosticsNotContains(closedDiagnostics))
              .collect(Collectors.toList());
      diagnosticRepo.put(uri, newDiagnostics);
    }
  }

  private Predicate<Diagnostic> diagnosticsNotContains(List<Diagnostic> closedFileRelatedDiagnostics) {
    // diagnostics address location is matched and not the value as the same diagnostics could be generated by diff files.
    return dia -> closedFileRelatedDiagnostics.stream().noneMatch(d1 -> d1 == dia);
  }

  private void updateDiagnosticRepo(String currentUri, Map<String, List<Diagnostic>> diagnostics) {
    LOG.debug("updateDiagnosticRepo " + currentUri + " " + diagnostics);
    synchronized (diagnosticRepo) {
      diagnostics.forEach((key, diagnosticList) -> {
        if (key.equals(currentUri)) {
          diagnosticRepo.put(currentUri, diagnosticList);
        } else {
          List<Diagnostic> oldList = diagnosticRepo.getOrDefault(key, Collections.emptyList());
          List<Diagnostic> newList = new ArrayList<>(oldList);
          newList.addAll(diagnosticList);
          diagnosticRepo.put(key, newList);
        }
      });
    }
  }
}
