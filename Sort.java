import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.FileAlreadyExistsException;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.LineRecordReader;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import hadooptrunk.InputSampler;
import hadooptrunk.TotalOrderPartitioner;

public final class Sort extends Configured implements Tool {
	public static void main(String[] args) throws Exception {
		System.exit(ToolRunner.run(new Configuration(), new Sort(), args));
	}

	private final List<Job> jobs = new ArrayList<Job>();

	@Override public int run(String[] args)
		throws ClassNotFoundException, IOException, InterruptedException
	{
		if (args.length < 2) {
			System.err.println(
				"Usage: " +Sort.class+ " <output directory> file [file...]");
			return 2;
		}

		FileSystem fs = FileSystem.get(getConf());

		Path outputDir = new Path(args[0]);
		if (fs.exists(outputDir) && !fs.getFileStatus(outputDir).isDir()) {
			System.err.printf(
				"ERROR: specified output directory '%s' is not a directory!\n",
				outputDir);
			return 2;
		}

		List<Path> files = new ArrayList<Path>(args.length - 1);
		for (String file : Arrays.asList(args).subList(1, args.length))
			files.add(new Path(file));

		if (new HashSet<Path>(files).size() < files.size()) {
			System.err.println("ERROR: duplicate file names specified!");
			return 2;
		}

		for (Path file : files) if (!fs.isFile(file)) {
			System.err.printf("ERROR: file '%s' is not a file!\n", file);
			return 2;
		}

		for (Path file : files)
			submitJob(file, outputDir);

		int ret = 0;
		for (Job job : jobs)
			if (!job.waitForCompletion(true))
				ret = 1;
		return ret;
	}

	private void submitJob(Path inputFile, Path outputDir)
		throws ClassNotFoundException, IOException, InterruptedException
	{
		Configuration conf = new Configuration(getConf());

		// Used by SortOutputFormat to construct the output filename
		conf.set(SortOutputFormat.INPUT_FILENAME_PROP, inputFile.getName());

		setSamplingConf(inputFile, conf);

		Job job = new Job(conf);

		job.setJarByClass  (Sort.class);
		job.setMapperClass (SortMapper.class);
		job.setReducerClass(SortReducer.class);

		job.setMapOutputKeyClass(LongWritable.class);
		job.setOutputKeyClass   (NullWritable.class);
		job.setOutputValueClass (Text.class);

		job.setInputFormatClass (SortInputFormat.class);
		job.setOutputFormatClass(SortOutputFormat.class);

		FileInputFormat .setInputPaths(job, inputFile);
		FileOutputFormat.setOutputPath(job, outputDir);

		job.setPartitionerClass(TotalOrderPartitioner.class);

		// Sample first so that we get a better partitioning
		sample(inputFile, job);

		job.submit();
		jobs.add(job);
	}

	private void setSamplingConf(Path inputFile, Configuration conf)
		throws IOException
	{
		Path inputDir = inputFile.getParent();
		inputDir = inputDir.makeQualified(inputDir.getFileSystem(conf));

		Path partition = new Path(inputDir, "_partitioning");
		TotalOrderPartitioner.setPartitionFile(conf, partition);

		try {
			URI partitionURI = new URI(partition.toString() + "#_partitioning");
			DistributedCache.addCacheFile(partitionURI, conf);
			DistributedCache.createSymlink(conf);
		} catch (URISyntaxException e) { assert false; }
	}

	private void sample(Path inputFile, Job job)
		throws ClassNotFoundException, IOException, InterruptedException
	{
		InputSampler.Sampler<LongWritable,Text> sampler =
			new InputSampler.IntervalSampler<LongWritable,Text>(0.01, 100);

		InputSampler.<LongWritable,Text>writePartitionFile(job, sampler);
	}
}

// The identity function is fine.
final class SortMapper extends Mapper<LongWritable,Text, LongWritable,Text> {}

final class SortReducer extends Reducer<LongWritable,Text, NullWritable,Text> {
	@Override protected void reduce(
			LongWritable ignored, Iterable<Text> lines,
			Reducer<LongWritable,Text, NullWritable,Text>.Context context)
		throws IOException, InterruptedException
	{
		for (Text line : lines)
			context.write(NullWritable.get(), line);
	}
}

final class SortInputFormat extends FileInputFormat<LongWritable,Text> {
	@Override public RecordReader<LongWritable,Text> createRecordReader(
			InputSplit split, TaskAttemptContext context)
		throws IOException, InterruptedException
	{
		RecordReader<LongWritable,Text> recReader = new SortInputRecordReader();
		recReader.initialize(split, context);
		return recReader;
	}

	// Like a LineRecordReader, but takes the Nth column of each line (separated
	// by tab characters), throwing an error if it can't be losslessly converted
	// to a long, and uses it as the key.
	final static class SortInputRecordReader extends LineRecordReader {
		private static final int N = 8; // The first column is N = 1

		private LongWritable key = null;

		@Override public boolean nextKeyValue() throws IOException {
			boolean read = super.nextKeyValue();
			if (!read)
				return false;

			Text keyCol = getNthColumn(getCurrentValue());
			if (keyCol == null)
				throw new RuntimeException(
					"Ran out of tabs at index " +super.getCurrentKey());

			key = new LongWritable(Long.parseLong(keyCol.toString()));
			return true;
		}

		private static Text getNthColumn(Text text) {
			int col = 0;
			int pos = 0;
			for (;;) {
				int npos = text.find("\t", pos);
				if (npos == -1)
					return null;

				if (++col == N) {
					// Grab [pos,npos).
					return new Text(Arrays.copyOfRange(text.getBytes(), pos, npos));
				}
				pos = npos + 1;
			}
		}

		@Override public LongWritable getCurrentKey() { return key; }
	}
}
final class SortOutputFormat extends TextOutputFormat<NullWritable,Text> {
	public static final String INPUT_FILENAME_PROP = "sort.input.filename";

	@Override public Path getDefaultWorkFile(
			TaskAttemptContext context, String ext)
		throws IOException
	{
		String filename  = context.getConfiguration().get(INPUT_FILENAME_PROP);
		String extension = ext.isEmpty() ? ext : "." + ext;
		String id        = context.getTaskAttemptID().toString();
		return new Path(getOutputPath(context), filename + "_" + id + extension);
	}

	// Allow the output directory to exist, so that we can make multiple jobs
	// that write into it.
	@Override public void checkOutputSpecs(JobContext job)
		throws FileAlreadyExistsException, IOException
	{}
}
