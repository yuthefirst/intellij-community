/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.command;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.openapi.application.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.psi.PsiFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ThrowableRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;

public abstract class WriteCommandAction<T> extends BaseActionRunnable<T> {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.command.WriteCommandAction");

  private static final String DEFAULT_COMMAND_NAME = "Undefined";
  private static final String DEFAULT_GROUP_ID = null;

  public interface Builder {
    @NotNull Builder withName(@Nullable String name);
    @NotNull Builder withGroupId(@Nullable String groupId);

    <E extends Throwable> void run(@NotNull ThrowableRunnable<E> action) throws E;
    <R, E extends Throwable> R compute(@NotNull ThrowableComputable<R, E> action) throws E;
  }

  private static class BuilderImpl implements Builder {
    private final Project myProject;
    private final PsiFile[] myFiles;
    private String myCommandName = DEFAULT_COMMAND_NAME;
    private String myGroupId = DEFAULT_GROUP_ID;

    private BuilderImpl(Project project, PsiFile... files) {
      myProject = project;
      myFiles = files;
    }

    @NotNull
    @Override
    public Builder withName(String name) {
      myCommandName = name;
      return this;
    }

    @NotNull
    @Override
    public Builder withGroupId(String groupId) {
      myGroupId = groupId;
      return this;
    }

    @Override
    public <E extends Throwable> void run(@NotNull final ThrowableRunnable<E> action) throws E {
      new WriteCommandAction(myProject, myCommandName, myGroupId, myFiles) {
        @Override
        protected void run(@NotNull Result result) throws Throwable {
          action.run();
        }
      }.execute();
    }

    @Override
    public <R, E extends Throwable> R compute(@NotNull final ThrowableComputable<R, E> action) throws E {
      return new WriteCommandAction<R>(myProject, myCommandName, myGroupId, myFiles) {
        @Override
        protected void run(@NotNull Result<R> result) throws Throwable {
          result.setResult(action.compute());
        }
      }.execute().getResultObject();
    }
  }

  @NotNull
  public static Builder writeCommandAction(Project project) {
    return new BuilderImpl(project);
  }

  @NotNull
  public static Builder writeCommandAction(@NotNull PsiFile first, @NotNull PsiFile... others) {
    return new BuilderImpl(first.getProject(), ArrayUtil.prepend(first, others));
  }

  private final String myCommandName;
  private final String myGroupID;
  private final Project myProject;
  private final PsiFile[] myPsiFiles;

  protected WriteCommandAction(@Nullable Project project, /*@NotNull*/ PsiFile... files) {
    this(project, DEFAULT_COMMAND_NAME, files);
  }

  protected WriteCommandAction(@Nullable Project project, @Nullable String commandName, /*@NotNull*/ PsiFile... files) {
    this(project, commandName, DEFAULT_GROUP_ID, files);
  }

  protected WriteCommandAction(@Nullable Project project, @Nullable String commandName, @Nullable String groupID, /*@NotNull*/ PsiFile... files) {
    myCommandName = commandName;
    myGroupID = groupID;
    myProject = project;
    if (files == null) LOG.warn("'files' parameter must not be null", new Throwable());
    myPsiFiles = files == null || files.length == 0 ? PsiFile.EMPTY_ARRAY : files;
  }

  public final Project getProject() {
    return myProject;
  }

  public final String getCommandName() {
    return myCommandName;
  }

  public String getGroupID() {
    return myGroupID;
  }

  @NotNull
  @Override
  public RunResult<T> execute() {
    Application application = ApplicationManager.getApplication();
    boolean dispatchThread = application.isDispatchThread();

    if (!dispatchThread && application.isReadAccessAllowed()) {
      LOG.error("Must not start write action from within read action in the other thread - deadlock is coming");
      throw new IllegalStateException();
    }

    final RunResult<T> result = new RunResult<T>(this);
    if (dispatchThread) {
      performWriteCommandAction(result);
    }
    else {
      try {
        TransactionGuard.getInstance().submitTransactionAndWait(() -> performWriteCommandAction(result));
      }
      catch (ProcessCanceledException ignored) { }
    }
    return result;
  }

  private void performWriteCommandAction(@NotNull RunResult<T> result) {
    if (!FileModificationService.getInstance().preparePsiElementsForWrite(Arrays.asList(myPsiFiles))) return;

    // this is needed to prevent memory leak, since the command is put into undo queue
    final RunResult[] results = {result};

    doExecuteCommand(() -> {
      //noinspection deprecation
      ApplicationManager.getApplication().runWriteAction(() -> {
        results[0].run();
        results[0] = null;
      });
    });
  }

  protected boolean isGlobalUndoAction() {
    return false;
  }

  protected UndoConfirmationPolicy getUndoConfirmationPolicy() {
    return UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION;
  }

  /**
   * See {@link CommandProcessor#executeCommand(Project, Runnable, String, Object, UndoConfirmationPolicy, boolean)} for details.
   */
  protected boolean shouldRecordActionForActiveDocument() {
    return true;
  }

  public void performCommand() throws Throwable {
    //this is needed to prevent memory leak, since command
    // is put into undo queue
    final RunResult[] results = {new RunResult<T>(this)};
    final Ref<Throwable> exception = new Ref<Throwable>();

    doExecuteCommand(() -> {
      exception.set(results[0].run().getThrowable());
      results[0] = null;
    });

    Throwable throwable = exception.get();
    if (throwable != null) throw throwable;
  }

  private void doExecuteCommand(final Runnable runnable) {
    Runnable wrappedRunnable = () -> {
      if (isGlobalUndoAction()) CommandProcessor.getInstance().markCurrentCommandAsGlobal(getProject());
      runnable.run();
    };
    CommandProcessor.getInstance().executeCommand(getProject(), wrappedRunnable, getCommandName(), getGroupID(),
                                                  getUndoConfirmationPolicy(), shouldRecordActionForActiveDocument());
  }

  /**
   * WriteCommandAction without result
   */
  public abstract static class Simple<T> extends WriteCommandAction<T> {
    protected Simple(Project project, /*@NotNull*/ PsiFile... files) {
      super(project, files);
    }

    protected Simple(Project project, String commandName, /*@NotNull*/ PsiFile... files) {
      super(project, commandName, files);
    }

    protected Simple(Project project, String name, String groupID, /*@NotNull*/ PsiFile... files) {
      super(project, name, groupID, files);
    }

    @Override
    protected void run(@NotNull Result<T> result) throws Throwable {
      run();
    }

    protected abstract void run() throws Throwable;
  }

  public static void runWriteCommandAction(Project project, @NotNull Runnable runnable) {
    runWriteCommandAction(project, DEFAULT_COMMAND_NAME, DEFAULT_GROUP_ID, runnable);
  }

  public static void runWriteCommandAction(Project project,
                                           @Nullable final String commandName,
                                           @Nullable final String groupID,
                                           @NotNull final Runnable runnable,
                                           @NotNull PsiFile... files) {
    new Simple(project, commandName, groupID, files) {
      @Override
      protected void run() throws Throwable {
        runnable.run();
      }
    }.execute();
  }

  @SuppressWarnings("LambdaUnfriendlyMethodOverload")
  public static <T> T runWriteCommandAction(Project project, @NotNull final Computable<T> computable) {
    return new WriteCommandAction<T>(project) {
      @Override
      protected void run(@NotNull Result<T> result) throws Throwable {
        result.setResult(computable.compute());
      }
    }.execute().getResultObject();
  }

  @SuppressWarnings("LambdaUnfriendlyMethodOverload")
  public static <T, E extends Throwable> T runWriteCommandAction(Project project, @NotNull final ThrowableComputable<T, E> computable) throws E {
    RunResult<T> result = new WriteCommandAction<T>(project, "") {
      @Override
      protected void run(@NotNull Result<T> result) throws Throwable {
        result.setResult(computable.compute());
      }
    }.execute();
    Throwable t = result.getThrowable();
    if (t != null) { @SuppressWarnings("unchecked") E e = (E)t; throw e; }
    return result.throwException().getResultObject();
  }

  //<editor-fold desc="Deprecated stuff.">
  /** @deprecated use {@link FileModificationService#preparePsiElementsForWrite(Collection)} (to be removed in IDEA 2018) */
  @SuppressWarnings("unused")
  public static boolean ensureFilesWritable(@NotNull Project project, @NotNull Collection<PsiFile> psiFiles) {
    return FileModificationService.getInstance().preparePsiElementsForWrite(psiFiles);
  }
  //</editor-fold>
}