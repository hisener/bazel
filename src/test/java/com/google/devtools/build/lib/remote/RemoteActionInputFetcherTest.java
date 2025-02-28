// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import build.bazel.remote.execution.v2.Digest;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.MetadataProvider;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.clock.JavaClock;
import com.google.devtools.build.lib.remote.common.BulkTransferException;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.devtools.build.lib.remote.util.InMemoryCacheClient;
import com.google.devtools.build.lib.remote.util.StaticMetadataProvider;
import com.google.devtools.build.lib.remote.util.TempPathGenerator;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.Options;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RemoteActionInputFetcher}. */
@RunWith(JUnit4.class)
public class RemoteActionInputFetcherTest {

  private static final DigestHashFunction HASH_FUNCTION = DigestHashFunction.SHA256;

  private Path execRoot;
  private TempPathGenerator tempPathGenerator;
  private ArtifactRoot artifactRoot;
  private RemoteOptions options;
  private DigestUtil digestUtil;

  @Before
  public void setUp() throws IOException {
    FileSystem fs = new InMemoryFileSystem(new JavaClock(), HASH_FUNCTION);
    execRoot = fs.getPath("/exec");
    execRoot.createDirectoryAndParents();
    Path tempDir = fs.getPath("/tmp");
    tempDir.createDirectoryAndParents();
    tempPathGenerator = new TempPathGenerator(tempDir);
    Path dev = fs.getPath("/dev");
    dev.createDirectory();
    dev.setWritable(false);
    artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "root");
    artifactRoot.getRoot().asPath().createDirectoryAndParents();
    options = Options.getDefaults(RemoteOptions.class);
    digestUtil = new DigestUtil(SyscallCache.NO_CACHE, HASH_FUNCTION);
  }

  @Test
  public void testFetching() throws Exception {
    // arrange
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<Digest, ByteString> cacheEntries = new HashMap<>();
    Artifact a1 = createRemoteArtifact("file1", "hello world", metadata, cacheEntries);
    Artifact a2 = createRemoteArtifact("file2", "fizz buzz", metadata, cacheEntries);
    MetadataProvider metadataProvider = new StaticMetadataProvider(metadata);
    RemoteCache remoteCache = newCache(options, digestUtil, cacheEntries);
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher("none", "none", remoteCache, execRoot, tempPathGenerator);

    // act
    wait(actionInputFetcher.prefetchFiles(metadata.keySet(), metadataProvider));

    // assert
    assertThat(FileSystemUtils.readContent(a1.getPath(), StandardCharsets.UTF_8))
        .isEqualTo("hello world");
    assertThat(a1.getPath().isExecutable()).isTrue();
    assertThat(FileSystemUtils.readContent(a2.getPath(), StandardCharsets.UTF_8))
        .isEqualTo("fizz buzz");
    assertThat(a2.getPath().isExecutable()).isTrue();
    assertThat(actionInputFetcher.downloadedFiles()).hasSize(2);
    assertThat(actionInputFetcher.downloadedFiles()).containsAtLeast(a1.getPath(), a2.getPath());
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void testStagingVirtualActionInput() throws Exception {
    // arrange
    MetadataProvider metadataProvider = new StaticMetadataProvider(new HashMap<>());
    RemoteCache remoteCache = newCache(options, digestUtil, new HashMap<>());
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher("none", "none", remoteCache, execRoot, tempPathGenerator);
    VirtualActionInput a = ActionsTestUtil.createVirtualActionInput("file1", "hello world");

    // act
    wait(actionInputFetcher.prefetchFiles(ImmutableList.of(a), metadataProvider));

    // assert
    Path p = execRoot.getRelative(a.getExecPath());
    assertThat(FileSystemUtils.readContent(p, StandardCharsets.UTF_8)).isEqualTo("hello world");
    assertThat(p.isExecutable()).isTrue();
    assertThat(actionInputFetcher.downloadedFiles()).isEmpty();
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void testStagingEmptyVirtualActionInput() throws Exception {
    // arrange
    MetadataProvider metadataProvider = new StaticMetadataProvider(new HashMap<>());
    RemoteCache remoteCache = newCache(options, digestUtil, new HashMap<>());
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher("none", "none", remoteCache, execRoot, tempPathGenerator);

    // act
    wait(
        actionInputFetcher.prefetchFiles(
            ImmutableList.of(VirtualActionInput.EMPTY_MARKER), metadataProvider));

    // assert that nothing happened
    assertThat(actionInputFetcher.downloadedFiles()).isEmpty();
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void testFileNotFound() throws Exception {
    // Test that we get an exception if an input file is missing

    // arrange
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Artifact a =
        createRemoteArtifact("file1", "hello world", metadata, /* cacheEntries= */ new HashMap<>());
    MetadataProvider metadataProvider = new StaticMetadataProvider(metadata);
    RemoteCache remoteCache = newCache(options, digestUtil, new HashMap<>());
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher("none", "none", remoteCache, execRoot, tempPathGenerator);

    // act
    assertThrows(
        BulkTransferException.class,
        () -> wait(actionInputFetcher.prefetchFiles(ImmutableList.of(a), metadataProvider)));

    // assert
    assertThat(actionInputFetcher.downloadedFiles()).isEmpty();
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void testIgnoreNoneRemoteFiles() throws Exception {
    // Test that files that are not remote are not downloaded

    // arrange
    Path p = execRoot.getRelative(artifactRoot.getExecPath()).getRelative("file1");
    FileSystemUtils.writeContent(p, StandardCharsets.UTF_8, "hello world");
    Artifact a = ActionsTestUtil.createArtifact(artifactRoot, p);
    FileArtifactValue f = FileArtifactValue.createForTesting(a);
    MetadataProvider metadataProvider = new StaticMetadataProvider(ImmutableMap.of(a, f));
    RemoteCache remoteCache = newCache(options, digestUtil, new HashMap<>());
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher("none", "none", remoteCache, execRoot, tempPathGenerator);

    // act
    wait(actionInputFetcher.prefetchFiles(ImmutableList.of(a), metadataProvider));

    // assert
    assertThat(actionInputFetcher.downloadedFiles()).isEmpty();
    assertThat(actionInputFetcher.downloadsInProgress()).isEmpty();
  }

  @Test
  public void testDownloadFile() throws Exception {
    // arrange
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<Digest, ByteString> cacheEntries = new HashMap<>();
    Artifact a1 = createRemoteArtifact("file1", "hello world", metadata, cacheEntries);
    RemoteCache remoteCache = newCache(options, digestUtil, cacheEntries);
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher("none", "none", remoteCache, execRoot, tempPathGenerator);

    // act
    actionInputFetcher.downloadFile(a1.getPath(), metadata.get(a1));

    // assert
    assertThat(FileSystemUtils.readContent(a1.getPath(), StandardCharsets.UTF_8))
        .isEqualTo("hello world");
    assertThat(a1.getPath().isExecutable()).isTrue();
    assertThat(a1.getPath().isReadable()).isTrue();
    assertThat(a1.getPath().isWritable()).isFalse();
  }

  @Test
  public void testDownloadFile_onInterrupt_deletePartialDownloadedFile() throws Exception {
    Semaphore startSemaphore = new Semaphore(0);
    Semaphore endSemaphore = new Semaphore(0);
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<Digest, ByteString> cacheEntries = new HashMap<>();
    Artifact a1 = createRemoteArtifact("file1", "hello world", metadata, cacheEntries);
    RemoteCache remoteCache = mock(RemoteCache.class);
    mockDownload(
        remoteCache,
        cacheEntries,
        () -> {
          startSemaphore.release();
          return SettableFuture.create(); // A future that never complete so we can interrupt later
        });
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher("none", "none", remoteCache, execRoot, tempPathGenerator);

    AtomicBoolean interrupted = new AtomicBoolean(false);
    Thread t =
        new Thread(
            () -> {
              try {
                actionInputFetcher.downloadFile(a1.getPath(), metadata.get(a1));
              } catch (IOException ignored) {
                interrupted.set(false);
              } catch (InterruptedException e) {
                interrupted.set(true);
              }
              endSemaphore.release();
            });
    t.start();
    startSemaphore.acquire();
    t.interrupt();
    endSemaphore.acquire();

    assertThat(interrupted.get()).isTrue();
    assertThat(a1.getPath().exists()).isFalse();
    assertThat(tempPathGenerator.getTempDir().getDirectoryEntries()).isEmpty();
  }

  @Test
  public void testPrefetchFiles_multipleThreads_downloadIsNotCancelledByOtherThreads()
      throws Exception {
    // Test multiple threads can share downloads, but do not cancel each other when interrupted

    // arrange
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<Digest, ByteString> cacheEntries = new HashMap<>();
    Artifact artifact = createRemoteArtifact("file1", "hello world", metadata, cacheEntries);
    MetadataProvider metadataProvider = new StaticMetadataProvider(metadata);
    SettableFuture<Void> download = SettableFuture.create();
    RemoteCache remoteCache = mock(RemoteCache.class);
    mockDownload(remoteCache, cacheEntries, () -> download);
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher("none", "none", remoteCache, execRoot, tempPathGenerator);
    Thread cancelledThread =
        new Thread(
            () -> {
              try {
                wait(
                    actionInputFetcher.prefetchFiles(ImmutableList.of(artifact), metadataProvider));
              } catch (IOException | InterruptedException ignored) {
                // do nothing
              }
            });

    AtomicBoolean successful = new AtomicBoolean(false);
    Thread successfulThread =
        new Thread(
            () -> {
              try {
                wait(
                    actionInputFetcher.prefetchFiles(ImmutableList.of(artifact), metadataProvider));
                successful.set(true);
              } catch (IOException | InterruptedException ignored) {
                // do nothing
              }
            });
    cancelledThread.start();
    successfulThread.start();
    while (true) {
      if (actionInputFetcher
              .getDownloadCache()
              .getSubscriberCount(execRoot.getRelative(artifact.getExecPath()))
          == 2) {
        break;
      }
    }

    // act
    cancelledThread.interrupt();
    cancelledThread.join();
    // simulate the download finishing
    assertThat(download.isCancelled()).isFalse();
    download.set(null);
    successfulThread.join();

    // assert
    assertThat(successful.get()).isTrue();
    assertThat(FileSystemUtils.readContent(artifact.getPath(), StandardCharsets.UTF_8))
        .isEqualTo("hello world");
  }

  @Test
  public void testPrefetchFiles_multipleThreads_downloadIsCancelled() throws Exception {
    // Test shared downloads are cancelled if all threads/callers are interrupted

    // arrange
    Map<ActionInput, FileArtifactValue> metadata = new HashMap<>();
    Map<Digest, ByteString> cacheEntries = new HashMap<>();
    Artifact artifact = createRemoteArtifact("file1", "hello world", metadata, cacheEntries);
    MetadataProvider metadataProvider = new StaticMetadataProvider(metadata);

    SettableFuture<Void> download = SettableFuture.create();
    RemoteCache remoteCache = mock(RemoteCache.class);
    mockDownload(remoteCache, cacheEntries, () -> download);
    RemoteActionInputFetcher actionInputFetcher =
        new RemoteActionInputFetcher("none", "none", remoteCache, execRoot, tempPathGenerator);

    Thread cancelledThread1 =
        new Thread(
            () -> {
              try {
                wait(
                    actionInputFetcher.prefetchFiles(ImmutableList.of(artifact), metadataProvider));
              } catch (IOException | InterruptedException ignored) {
                // do nothing
              }
            });

    Thread cancelledThread2 =
        new Thread(
            () -> {
              try {
                wait(
                    actionInputFetcher.prefetchFiles(ImmutableList.of(artifact), metadataProvider));
              } catch (IOException | InterruptedException ignored) {
                // do nothing
              }
            });

    // act
    cancelledThread1.start();
    cancelledThread2.start();
    cancelledThread1.interrupt();
    cancelledThread2.interrupt();
    cancelledThread1.join();
    cancelledThread2.join();

    // assert
    assertThat(download.isCancelled()).isTrue();
    assertThat(artifact.getPath().exists()).isFalse();
    assertThat(tempPathGenerator.getTempDir().getDirectoryEntries()).isEmpty();
  }

  private Artifact createRemoteArtifact(
      String pathFragment,
      String contents,
      Map<ActionInput, FileArtifactValue> metadata,
      Map<Digest, ByteString> cacheEntries) {
    Path p = artifactRoot.getRoot().getRelative(pathFragment);
    Artifact a = ActionsTestUtil.createArtifact(artifactRoot, p);
    byte[] b = contents.getBytes(StandardCharsets.UTF_8);
    HashCode h = HASH_FUNCTION.getHashFunction().hashBytes(b);
    FileArtifactValue f =
        new RemoteFileArtifactValue(h.asBytes(), b.length, /* locationIndex= */ 1, "action-id");
    metadata.put(a, f);
    cacheEntries.put(DigestUtil.buildDigest(h.asBytes(), b.length), ByteString.copyFrom(b));
    return a;
  }

  private RemoteCache newCache(
      RemoteOptions options, DigestUtil digestUtil, Map<Digest, ByteString> cacheEntries) {
    Map<Digest, byte[]> cacheEntriesByteArray =
        Maps.newHashMapWithExpectedSize(cacheEntries.size());
    for (Map.Entry<Digest, ByteString> entry : cacheEntries.entrySet()) {
      cacheEntriesByteArray.put(entry.getKey(), entry.getValue().toByteArray());
    }
    return new RemoteCache(new InMemoryCacheClient(cacheEntriesByteArray), options, digestUtil);
  }

  private static void wait(ListenableFuture<Void> future) throws IOException, InterruptedException {
    try {
      future.get();
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause != null) {
        throwIfInstanceOf(cause, IOException.class);
        throwIfInstanceOf(cause, InterruptedException.class);
        throwIfInstanceOf(cause, RuntimeException.class);
      }
      throw new IOException(e);
    } catch (InterruptedException e) {
      future.cancel(/*mayInterruptIfRunning=*/ true);
      throw e;
    }
  }

  private static void mockDownload(
      RemoteCache remoteCache,
      Map<Digest, ByteString> cacheEntries,
      Supplier<ListenableFuture<Void>> resultSupplier)
      throws IOException {
    when(remoteCache.downloadFile(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Path path = invocation.getArgument(1);
              Digest digest = invocation.getArgument(2);
              ByteString content = cacheEntries.get(digest);
              if (content == null) {
                return Futures.immediateFailedFuture(new IOException("Not found"));
              }
              FileSystemUtils.writeContent(path, content.toByteArray());
              return resultSupplier.get();
            });
  }
}
