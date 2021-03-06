/*
 * Copyright (c) 2000-2006 JetBrains s.r.o. All Rights Reserved.
 */

package net.sourceforge.transparent.ChangeManagement;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.diff.impl.patch.formove.FilePathComparator;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.WaitForProgressToShow;
import com.intellij.vcsUtil.VcsUtil;
import net.sourceforge.transparent.*;
import net.sourceforge.transparent.exceptions.ClearCaseException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

import static com.intellij.util.containers.ContainerUtil.map;
import static net.sourceforge.transparent.TransparentVcs.MERGE_CONFLICT;
import static net.sourceforge.transparent.TransparentVcs.SUCCESSFUL_CHECKOUT;

public class CCaseChangeProvider implements ChangeProvider {
  public static final Key<Object> ourVersionedKey = new Key<>("CCASE_VERSIONED");
  @NonNls private final static String REMINDER_TITLE = "Reminder";
  @NonNls private final static String REMINDER_TEXT = "Project started with ClearCase configured to be in the Offline mode.";

  @NonNls private final static String COLLECT_MSG = "Collecting writable files";
  @NonNls private final static String SEARCHNEW_MSG = "Searching New";

  @NonNls private final static String ESTABLISH_CONNECTION_FAIL_SIG = "Unable to establish connection to snapshot view";
  @NonNls private final static String UNABLE_OPEN_VIEW_SSIG = "Unable to open snapshot view";

  @NonNls private final static String FAIL_2_CONNECT_MSG = "Failed to connect to ClearCase Server: ";
  @NonNls private final static String FAIL_2_CONNECT_TITLE = "Server Connection Problem";
  @NonNls private final static String FAIL_2_START_MSG = "Failed to start Cleartool. Please check ClearCase installation or current View's settings";
  @NonNls private final static String FAIL_2_START_VIEW_MSG = "Failed to start Cleartool. Please check module's View settings";

  @NonNls private final static String LIST_CHECKOUTS_CMD = "lsco";
  @NonNls private final static String CURR_USER_ONLY_SWITCH = "-me";
  @NonNls private final static String CURR_VIEW_ONLY_SWITCH = "-cview";
  @NonNls private final static String SHORT_SWITCH = "-short";
  @NonNls private final static String RECURSE_SWITCH = "-recurse";

  /**
   * If amount of writable files during the batch call exceeds this number,
   * switch from iterative calls to cleartool's LS command to the different
   * scheme: call "cleartool findcheckouts" to find all "honestly" modified
   * files, others are considered NEW (since we will not analyze them for
   * "HIJACKED" status).
   */
  private static final int MAX_FILES_FOR_ITERATIVE_STATUS = 200;

  private static final Logger LOG = Logger.getInstance("#net.sourceforge.transparent.ChangeManagement.CCaseChangeProvider");

  private final Project project;
  private final CCaseViewsManager myViewManager;
  private final TransparentVcs host;
  private CCaseConfig config;
  private CCaseSharedConfig mySharedConfig;
  private ProgressIndicator progress;
  private boolean isBatchUpdate;
  private boolean isFirstShow;

  private final HashSet<String> filesWritable = new HashSet<>();
  private final HashSet<String> filesNew = new HashSet<>();
  private final HashSet<String> filesChanged = new HashSet<>();
  private final HashSet<String> filesHijacked = new HashSet<>();
  private final HashSet<String> filesIgnored = new HashSet<>();
  private final HashSet<String> filesMerge = new HashSet<>();
  private final HashSet<String> filesLocallyDeleted = new HashSet<>();
  private final ChangeListManager myChangeListManager;
  private TreeSet<VirtualFile> myDirs;

  public CCaseChangeProvider( Project project, TransparentVcs hostVcs )
  {
    this.project = project;
    myViewManager = CCaseViewsManager.getInstance(project);
    host = hostVcs;
    isFirstShow = true;
    myChangeListManager = ChangeListManager.getInstance(this.project);
    myDirs = new TreeSet<>(FilePathComparator.getInstance());
  }

  public boolean isModifiedDocumentTrackingRequired() { return false;  }

  public void doCleanup(final List<VirtualFile> files) {
  }

  public void getChanges(@NotNull final VcsDirtyScope dirtyScope, @NotNull final ChangelistBuilder builder, @NotNull final ProgressIndicator progressIndicator,
                         @NotNull final ChangeListManagerGate addGate) throws VcsException {
    myDirs.clear();
    //-------------------------------------------------------------------------
    //  Protect ourselves from the calls which come during the unsafe project
    //  phases like unload or reload.
    //-------------------------------------------------------------------------
    if( project.isDisposed() )
      return;

    logChangesContent( dirtyScope );

    //  Do not perform any actions if we have no VSS-related
    //  content roots configured.
    if( ProjectLevelVcsManager.getInstance( project ).getDirectoryMappings( host ).size() == 0 )
      return;

    config = host.getConfig();
    mySharedConfig = CCaseSharedConfig.getInstance(project);
    progress = progressIndicator;
    isBatchUpdate = isBatchUpdate( dirtyScope );

    showOptionalReminder();
    initInternals();
    isFirstShow = false;

    try
    {
      iterateOverRecursiveFolders( dirtyScope );
      iterateOverDirtyDirectories( dirtyScope );
      iterateOverDirtyFiles( dirtyScope );

      //  Perform status computation only if we operate in the online mode.
      //  For offline mode just display the last valid state (for new and
      //  modified files, others are hijacked).
      if(! config.isOffline() ) {
        if(isBatchUpdate && config.synchActivitiesOnRefresh) {
          myViewManager.extractViewActivities();
          myViewManager.synchActivities2ChangeLists(addGate);
        }
        computeStatuses(dirtyScope);
      } else {
        restoreStatusesFromCached();
      }
      processStatusExceptions();

      getUnversioned();
      addCheckedOutFolders();

      //-----------------------------------------------------------------------
      //  For an UCM view we must determine the corresponding changes list name
      //  which is associated with the "activity" of the particular view.
      //-----------------------------------------------------------------------
      if(mySharedConfig.isUseUcmModel()) {
        setActivityInfoOnChangedFiles();
      }

      addLocallyDeletedFiles(builder);
      addAddedFiles( builder );
      addChangedFiles( builder );
      addRemovedFiles( builder );
      addIgnoredFiles( builder );
      addMergeConflictFiles( builder );
    }
    catch( ClearCaseException e )
    {
      String excMessage = e.getMessage();
      excMessage = excMessage == null ? "" : excMessage;
      @NonNls String message = FAIL_2_CONNECT_MSG + excMessage;

      if( TransparentVcs.isServerDownMessage( excMessage ))
      {
        message += "\n\nSwitching to the offline mode";
        config.setOfflineMode(true);
      }
      else
      if( excMessage.contains( UNABLE_OPEN_VIEW_SSIG ) ||
          excMessage.contains( ESTABLISH_CONNECTION_FAIL_SIG ) )
      {
        message = FAIL_2_START_VIEW_MSG + excMessage;
      }

      final String msg = message;
      LOG.info(e);
      throw new VcsException(msg);
    }
    catch( RuntimeException e )
    {
      @NonNls final String message = FAIL_2_START_MSG + ": " + e.getMessage();
      LOG.info(e);
      throw new VcsException(message);
    }
    finally
    {
      TransparentVcs.LOG.debug( "-- EndChangeProvider| New: " + filesNew.size() + ", modified: " + filesChanged.size() +
                                ", hijacked:" + filesHijacked.size() + ", ignored: " + filesIgnored.size() );
    }
  }

  private void addCheckedOutFolders() {
    final Set<String> checkedOutFolders = new HashSet<>(host.getCheckedOutFolders());
    for (String dir : checkedOutFolders) {
      if (host.renamedFolders.containsKey(dir) || host.renamedFolders.containsValue(dir)) continue;
      final File file = new File(dir);
      final Status status = file.exists() ? host.getStatus(file) : null;
      if (Status.HIJACKED.equals(status) || Status.CHECKED_OUT.equals(status)) {
        filesChanged.add(dir);
      } else {
        host.undoCheckout(dir);
      }
    }
  }

  private void getUnversioned() {
    for (VirtualFile dir : myDirs) {
      if (host.renamedFolders.containsKey(dir.getPath())) continue;
      final Status status = host.getStatus(dir);
      if (Status.NOT_AN_ELEMENT.equals(status)) {
        filesNew.add(dir.getPath());
        dir.putUserData(ourVersionedKey, null);
      } else {
        dir.putUserData(ourVersionedKey, Boolean.TRUE);
        if (Status.HIJACKED.equals(status) || Status.CHECKED_OUT.equals(status)) {
          filesChanged.add(dir.getPath());
          host.checkedOutFolders.add(dir.getPath());
        }
      }
    }
  }

  /**
   * When we start for the very first time - show reminder that user possibly
   * forgot that last time he set option to "Work offline".
   */
  private void showOptionalReminder()
  {
    if( isBatchUpdate && isFirstShow && config.isOffline() )
    {
      WaitForProgressToShow.runOrInvokeLaterAboveProgress(() -> Messages.showWarningDialog(project, REMINDER_TEXT, REMINDER_TITLE ), null, project);
    }
  }

  /**
   *  Iterate over the project structure, find all writable files in the project,
   *  and check their status against the VSS repository. If file exists in the repository
   *  it is assigned "changed" status, otherwise it has "new" status.
   */
  private void iterateOverRecursiveFolders( final VcsDirtyScope dirtyScope )
  {
    for( FilePath path : dirtyScope.getRecursivelyDirtyDirectories() )
    {
      LOG.debug( "-- ChangeProvider - Iterating over [content] root: " + path.getPath() );
      if( progress != null )
        progress.setText( COLLECT_MSG );

      collectWritableFiles(path);

      LOG.debug( "-- ChangeProvider - Total: " + filesWritable.size() + " writable files after the last root." );
      if( progress != null )
        progress.setText( SEARCHNEW_MSG );
    }
  }

  /**
   *  Deleted and New folders are marked as dirty too and we provide here
   *  special processing for them.
   */
  private void iterateOverDirtyDirectories( final VcsDirtyScope dirtyScope )
  {
    for( FilePath path : dirtyScope.getDirtyFiles() )
    {
      String fileName = path.getPath();
      VirtualFile file = path.getVirtualFile();

      //  make sure that:
      //  - a file is a folder which exists (VFS has a valid ref to it)
      //  - it is under out vcs and is not in the ignore list
      if( path.isDirectory() && (file != null) )
      {
        if( host.isFileIgnored( file ))
          filesIgnored.add( fileName );
        else
        {
          String refName = host.discoverOldName(fileName);

          //  Check that folder physically exists.
          if( !host.fileExistsInVcs( refName ))
            filesNew.add( fileName );
          else
            //  NB: Do not put to the "Changed" list those folders which are under
            //      the renamed one since we will have troubles in checking such
            //      folders in (it is useless, BTW).
            //      Simultaneously, this prevents valid processing of renamed folders
            //      that are under another renamed folders.
            //  Todo Inner rename.
            if( !refName.equals( fileName ) && !isUnderRenamedFolder( fileName ) )
              filesChanged.add( fileName );
        }
      }
    }
  }

  private void iterateOverDirtyFiles( final VcsDirtyScope scope )
  {
    for( FilePath path : scope.getDirtyFiles() )
    {
      String fileName = path.getPath();
      VirtualFile file = path.getVirtualFile();

      if( host.isFileIgnored(file))
        filesIgnored.add( fileName );
      else
//        if( isFileCCaseProcessable( file ) && isProperNotification( path ) )
        if( (file != null) && file.isWritable() && isProperNotification( path ) )
          filesWritable.add( fileName );
    }
  }

  /**
   * Iterate over the project structure and collect two types of files:
   * - writable files, they are the subject for subsequent analysis
   * - "ignored" files - which will be shown in a separate changes folder.
   */
  private void collectWritableFiles( final FilePath filePath ) {
    final CCaseWriteableAndUnversionedCollector collector = new CCaseWriteableAndUnversionedCollector(project, host);
    collector.collectWritableFiles(filePath);

    filesIgnored.addAll(collector.getFilesIgnored());
    filesWritable.addAll(collector.getFilesWritable());
    myDirs.addAll(collector.getDirs());
  }

  private void computeStatuses(VcsDirtyScope dirtyScope) {
    LOG.debug( "---ChangeProvider - " + filesIgnored.size() + " ignored files accumulated so far.");

    if (filesWritable.size() < MAX_FILES_FOR_ITERATIVE_STATUS) {
      analyzeWritableFiles( filesWritable );
    } else {
      final Collection<VirtualFile> roots = dirtyScope.getAffectedContentRoots();
      List<String> pathsAsString = map(roots, VirtualFile::getPath);
      final StatusMultipleProcessor processor = new StatusMultipleProcessor(pathsAsString);
      processor.setRecursive(true);
      processor.setViewOnly(true);
      processor.execute();
      processViewStatusResults(processor);
    }
  }

  private void processViewStatusResults(final StatusMultipleProcessor processor) {
    for (String path : processor.getUnversioned()) {
      filesNew.add(path);
    }
    for (String path : processor.getCheckoutFiles()) {
      if (host.renamedFiles.containsValue(path) || host.renamedFolders.containsValue(path)) continue;
      filesChanged.add(path);
    }
    for (String path : processor.getHijackedFiles()) {
      final String oldName = host.discoverOldName(path);
      if (path.equals(oldName)) {
        filesHijacked.add(path);
      } else {
        filesChanged.add(path);
      }
    }
    for (String path : processor.getLocallyDeleted()) {
      final String newName = host.discoverNewName(path);
      // map holds new -> old
      if (! host.renamedFiles.containsKey(newName)) {
        if (! path.equals(newName)) {
          //host.renamedFiles.put(newName, path);
          final File newFile = new File(newName);

          if (newFile.exists() && newFile.canWrite()) {
            filesChanged.add(newName);
          }
          continue;
        } if (! host.renamedFolders.containsValue(newName)) {
          filesLocallyDeleted.add(path);
        }
      } else {
        filesChanged.add(newName);
      }
    }
  }

  private void analyzeWritableFiles( HashSet<String> writables )
  {
    if( writables.size() == 0 )
      return;

    //-------------------------------------------------------------------------
    //  1. Exclude those files for which status is known apriori:
    //    - file has status "changed" right after it was checked out
    //    - file has status "Merge Conflict" if that was indicated during
    //      the last commit operation.
    //  2. Guess file status given its previous file status.
    //-------------------------------------------------------------------------
    List<String> writableFiles = filterOutMarkedFiles( writables );

    //-------------------------------------------------------------------------
    List<String> refNames = new ArrayList<>();
    for( String file : writableFiles )
    {
      String legalName = host.discoverOldName(file).replace('\\', '/');
      refNames.add(legalName);
    }

    LOG.debug( "ChangeProvider - Analyzing writables in batch mode using CLEARTOOL on " + writables.size() + " files." );

    StatusMultipleProcessor processor = new StatusMultipleProcessor( refNames );
    processor.execute();
    LOG.debug( "ChangeProvider - \"CLEARTOOL LS\" batch command finished." );

    processViewStatusResults(processor);
  }

  /**
   * Do not analyze the file if we know that this file just has been
   * successfully checked out from the repository, its RO status is
   * writable and it is ready for editing.
   */
  private List<String> filterOutMarkedFiles( HashSet<String> list )
  {
    ArrayList<String> files = new ArrayList<>();
    for( String path : list )
    {
      VirtualFile file = VcsUtil.getVirtualFile( path );

      if( file.getUserData( SUCCESSFUL_CHECKOUT ) != null  )
      {
        //  Do not forget to delete this property right after the change
        //  is classified, otherwise this file will always be determined
        //  as modified.
        file.putUserData( SUCCESSFUL_CHECKOUT, null );
        filesChanged.add( file.getPath() );
      }
      else
      if( file.getUserData( MERGE_CONFLICT ) != null )
      {
        filesMerge.add( file.getPath() );
      }
      else
      {
        files.add( path );
      }
    }
    return files;
  }

  /**
   * Process exceptions of different kind when normal computation of file
   * statuses is cheated by the IDEA:
   * 1. "Extract Superclass" refactoring with "Rename original class" option set.
   *    Refactoring renamed the original class (right) but writes new content to
   *    the file with the olf name (fuck!).
   *    Remedy: Find such file in the list of "Changed" files, check whether its
   *            name is in the list of New files (from VFSListener), and check
   *            whether its name is in the record for renamed files, then move
   *            it into "New" files list.
   */
  private void processStatusExceptions()
  {
    // 1.
    for( Iterator<String> it = filesChanged.iterator(); it.hasNext(); )
    {
      String fileName = it.next();
      if( host.isNewOverRenamed( fileName ) )
      {
        it.remove();
        filesNew.add( fileName );
      }
    }
  }

  private void restoreStatusesFromCached()
  {
    for( String fileName : filesWritable )
    {
      if( host.containsModified( fileName ))
      {
        filesChanged.add( fileName );
      }
      else
      if( host.containsNew( fileName ) )
      {
        filesNew.add( fileName );
      }
      else
      {
        filesHijacked.add( fileName );
      }
    }
  }

  private void addLocallyDeletedFiles(final ChangelistBuilder builder) {
    for (String file : filesLocallyDeleted) {
      FilePath path = VcsUtil.getFilePath(file);
      builder.processLocallyDeletedFile(path);
    }
  }

  /**
   * File is either:
   * - "new" - it is not contained in the repository, but host contains
   *           a record about it (that is, it was manually moved to the
   *           list of files to be added to the commit.
   * - "unversioned" - it is not contained in the repository yet.
   */
  private void addAddedFiles( final ChangelistBuilder builder )
  {
    for( String fileName : filesNew )
    {
      //  In the case of file rename or parent folder rename we should
      //  refer to the list of new files by the
      final String refName = host.discoverOldName(fileName);

      if (! refName.equals(fileName)) {
        //filesChanged.add(fileName);
        continue;
      }
      //  New file could be added AFTER and BEFORE e.g. the package rename.
      if( host.containsNew( fileName ) || host.containsNew( refName ))
      {
        FilePath path = VcsUtil.getFilePath( fileName );
        String activity = findActivityForFile( path, path );
        builder.processChangeInList( new Change( null, new CurrentContentRevision( path ) ), activity, TransparentVcs.getKey());
      }
      else
      {
        builder.processUnversionedFile( VcsUtil.getVirtualFile( fileName ) );
      }
    }
  }

  /**
   * For each changed file which has no known checkout activity find it
   * by processing "describe" command.
   */
  private void setActivityInfoOnChangedFiles()
  {
    List<String> filesToCheck = new ArrayList<>();
    for( String fileName : filesChanged )
    {
      if( myViewManager.getCheckoutActivityForFile( fileName ) == null )
        filesToCheck.add( fileName );
    }

    setActivityInfoOnChangedFiles( filesToCheck );
  }

  /**
   * For each file in list find its activity by processing "describe" command.
   * @param files
   */
  public void setActivityInfoOnChangedFiles( final List<String> files )
  {
    List<String> refFilesToCheck = new ArrayList<>();
    for( String fileName : files )
    {
      refFilesToCheck.add(host.discoverOldName(fileName));
    }

    DescribeMultipleProcessor processor = new DescribeMultipleProcessor( refFilesToCheck );
    processor.execute();

    boolean hasAlreadyReloadedActivities = false;

    for( int i = 0; i < refFilesToCheck.size(); i++ )
    {
      String activity = processor.getActivity( refFilesToCheck.get( i ) );
      if( activity != null )
      {
        String activityName = myViewManager.getActivityDisplayName( activity );
        if( activityName == null )
        {
          //  Something has changed outside the IDEA - we did not recognize the
          //  activity name. Thus we need to synchronize views and activities
          //  all together to properly move the change into its changelist.
          if( !hasAlreadyReloadedActivities )
          {
            hasAlreadyReloadedActivities = true;
            myViewManager.extractViewActivities();
          }

          activityName = myViewManager.getActivityDisplayName( activity );
        }

        if( activityName != null )
          myViewManager.addFile2Changelist( files.get( i ), activityName );
      }
    }
  }

  /**
   * Add all files which were determined to be changed (somehow - modified,
   * renamed, etc) and folders which were renamed.
   * NB: adding folders information actually works only in either batch refresh
   *     of statuses or when some folder appears in the list of changes.
   */
  private void addChangedFiles( final ChangelistBuilder builder )
  {
    filesChanged.removeAll(host.renamedFolders.keySet());
    //filesChanged.removeAll(host.renamedFiles.keySet());

    for( String fileName : filesChanged )
    {
      String validRefName = host.discoverOldName(fileName);
      add2ChangeList( builder, FileStatus.MODIFIED, fileName, validRefName );
    }

    for( String fileName : filesHijacked )
    {
      String validRefName = host.discoverOldName(fileName);
      add2ChangeList( builder, FileStatus.HIJACKED, fileName, validRefName );
    }

    for( String folderName : host.renamedFolders.keySet() )
    {
      String oldFolderName = host.renamedFolders.get( folderName );

      final FilePath refPath = VcsUtil.getFilePath( oldFolderName, true );
      final FilePath currPath = VcsUtil.getFilePath( folderName ); // == refPath if no rename occured
      String activity = findActivityForFile( refPath, currPath );

      CCaseContentRevision revision = ContentRevisionFactory.getRevision( refPath, project );
      builder.processChangeInList( new Change( revision, new CurrentContentRevision( currPath ), FileStatus.MODIFIED ), activity, TransparentVcs.getKey());
    }
  }

  private void add2ChangeList( final ChangelistBuilder builder, FileStatus status,
                               String fileName, String validRefName )
  {
    final FilePath refPath = VcsUtil.getFilePath( validRefName );
    final FilePath currPath = VcsUtil.getFilePath( fileName ); // == refPath if no rename occured
    String activity = findActivityForFile( refPath, currPath );

    CCaseContentRevision revision = ContentRevisionFactory.getRevision( refPath, project );
    builder.processChangeInList( new Change( revision, new CurrentContentRevision( currPath ), status ), activity, TransparentVcs.getKey());
  }

  private void addRemovedFiles( final ChangelistBuilder builder )
  {
    //  Use additional set to remove async modification conflicts
    final HashSet<String> files = new HashSet<>(host.removedFolders);
    for( String path : files )
      builder.processLocallyDeletedFile( VcsUtil.getFilePath( path, true ) );

    files.clear();
    files.addAll( host.removedFiles );
    for( String path : files )
      builder.processLocallyDeletedFile( VcsUtil.getFilePath( path, false ) );

    files.clear();
    files.addAll( host.deletedFolders );
    for( String path : files )
    {
      FilePath refPath = VcsUtil.getFilePath( path, true );
      String activity = findActivityForFile( refPath, refPath );
      builder.processChangeInList( new Change( new CurrentContentRevision( refPath ), null, FileStatus.DELETED ), activity, TransparentVcs.getKey());
    }

    files.clear();
    files.addAll( host.deletedFiles );
    for( String path : files )
    {
      FilePath refPath = VcsUtil.getFilePath( path, false );
      CCaseContentRevision revision = ContentRevisionFactory.getRevision( refPath, project );
      String activity = findActivityForFile( refPath, refPath );

      builder.processChangeInList( new Change( revision, null, FileStatus.DELETED ), activity, TransparentVcs.getKey());
    }
  }

  private void addIgnoredFiles( final ChangelistBuilder builder )
  {
    for( String path : filesIgnored )
      builder.processIgnoredFile( VcsUtil.getVirtualFile( path ) );
  }

  private void addMergeConflictFiles( final ChangelistBuilder builder )
  {
    for( String path : filesMerge )
    {
      final FilePath fp = VcsUtil.getFilePath( path );
      CCaseContentRevision revision = ContentRevisionFactory.getRevision( fp, project );
      String activity = findActivityForFile( fp, fp );
      builder.processChangeInList( new Change( revision, new CurrentContentRevision( fp ), FileStatus.MERGED_WITH_CONFLICTS ), activity, TransparentVcs.getKey());
    }
  }

  /**
   * For the renamed or moved file we receive two change requests: one for
   * the old file and one for the new one. For renamed file old request differs
   * in filename, for the moved one - in parent path name. This request must be
   * ignored since all preliminary information is already accumulated.
   */
  private static boolean isProperNotification( final FilePath filePath )
  {
    String oldName = filePath.getName();
    String newName = (filePath.getVirtualFile() == null) ? "" : filePath.getVirtualFile().getName();
    String oldParent = (filePath.getVirtualFileParent() == null) ? "" : filePath.getVirtualFileParent().getPath();
    String newParent = filePath.getPath().substring( 0, filePath.getPath().length() - oldName.length() - 1 );

    //  Check the case when the file is deleted - its FilePath's VirtualFile
    //  component is null and thus new name is empty.
    return newParent.equals( oldParent ) &&
           ( newName.equals( oldName ) || (StringUtil.isEmpty( newName ) && StringUtil.isEmpty( oldName ) ) );
  }

  private void initInternals()
  {
    filesLocallyDeleted.clear();
    filesWritable.clear();
    filesNew.clear();
    filesChanged.clear();
    filesHijacked.clear();
    filesIgnored.clear();
    filesMerge.clear();
  }

  /**
   * Request for changes is "batch" if all folders from
   * VcsDirtyScope.getRecursivelyDirtyDirectories() are content roots. 
   */
  private boolean isBatchUpdate( VcsDirtyScope scope )
  {
    ProjectLevelVcsManager mgr = ProjectLevelVcsManager.getInstance( project );
    VirtualFile[] roots = mgr.getRootsUnderVcs( host );
    for (FilePath path : scope.getRecursivelyDirtyDirectories()) {
      for (VirtualFile root : roots) {
        VirtualFile vfScopePath = path.getVirtualFile();
        //  VFile may be null in the case of deleted folders (IDEADEV-18855)
        if (vfScopePath != null && vfScopePath.getPath().equalsIgnoreCase(root.getPath())) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean isUnderRenamedFolder( String fileName ) {
    return getUnderRenamedFolder(fileName) != null;
  }

  @Nullable
  private String getUnderRenamedFolder(String fileName) {
    for (String folder : host.renamedFolders.keySet()) {
      if (fileName.startsWith(folder)) {
        return folder;
      }
    }
    return null;
  }

  static boolean isValidFile( VirtualFile file )
  {
    return (file != null) && file.isWritable() && !file.isDirectory();
  }

  @Nullable
  private String findActivityForFile( FilePath refPath, final FilePath currPath )
  {
    String activity = null;

    //  Computing the activity name (to be used as the Changelist name) is defined
    //  only if the "UCM" mode is checked on for the view. Otherwise IDEA's changelist
    //  preserve their local (IDE-wide) semantics.
    if( mySharedConfig.isUseUcmModel() && myViewManager.isUcmViewForFile( refPath ) )
    {
      //  First check whether the file was checked out under IDEA, we've
      //  parsed the "co" output and extracted the name of the activity under
      //  which the file had been checked out.
      activity = myViewManager.getCheckoutActivityForFile( refPath.getPath() );
      if( activity == null )
      {
        //  Check the changelist which contain this particular file -
        //  if there is no such, then this file (change) is processed for the
        //  very first time and we need to find (or create) the appropriate
        //  change list for it.
        Change change = myChangeListManager.getChange( currPath );
        if( change == null )
        {
          //  Possibly the change had been made outside.
          //  1. Find the view responsible for this file.
          //  2. Take it current activity
          //  3. Find or create a change named after this activity.
          //  4. Remember that this file was first changed in this activity.

          activity = myViewManager.getActivityOfViewOfFile( currPath );

          if( activity == null )
            throw new NullPointerException( "Illegal (NULL) activity name from ViewInfo for an UCM view.");

          myViewManager.addFile2Changelist( refPath.getPath(), activity );
        }
      }
    }

    return activity;
  }

  private static void logChangesContent( final VcsDirtyScope scope )
  {
    if (! LOG.isDebugEnabled()) return;
    LOG.debug( "-- ChangeProvider: Dirty files: " + scope.getDirtyFiles().size() );
    if( scope.getDirtyFiles().size() > 0 )
      LOG.debug( " == " + extMasks( scope.getDirtyFiles() ) );
    LOG.debug( ", dirty recursive directories: " + scope.getRecursivelyDirtyDirectories().size() );

    for( FilePath path : scope.getDirtyFiles() )
      LOG.debug( "                                " + path.getPath() );
    LOG.debug( "                                ---" );
    for( FilePath path : scope.getRecursivelyDirtyDirectories() )
      LOG.debug( "                                " + path.getPath() );
  }

  private static String extMasks( Set<FilePath> scope )
  {
    HashMap<String, Integer> masks = new HashMap<>();
    for( FilePath path : scope )
    {
      int index = path.getName().lastIndexOf( '.' );
      if( index != -1 )
      {
        String ext = path.getName().substring( index );
        Integer count = masks.get( ext );
        masks.put( ext, (count == null) ? 1 : (count.intValue() + 1 ) );
      }
    }

    String masksStr = "";
    for( String ext : masks.keySet() )
    {
      masksStr += ext + " - " + masks.get( ext ).intValue() + "; ";
    }
    return masksStr;
  }
}
