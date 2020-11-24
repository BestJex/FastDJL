/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.djl.examples.training.transferlearning;

import ai.djl.Application;
import ai.djl.Device;
import ai.djl.MalformedModelException;
import ai.djl.Model;
import ai.djl.ModelException;
import ai.djl.basicdataset.Cifar10;
import ai.djl.basicmodelzoo.BasicModelZoo;
import ai.djl.basicmodelzoo.cv.classification.ResNetV1;
import com.djl.examples.training.util.Arguments;
import ai.djl.inference.Predictor;
import ai.djl.metric.Metrics;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.transform.Normalize;
import ai.djl.modality.cv.transform.ToTensor;
import ai.djl.modality.cv.translator.ImageClassificationTranslator;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Block;
import ai.djl.nn.Blocks;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.SymbolBlock;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.EasyTrain;
import ai.djl.training.Trainer;
import ai.djl.training.TrainingResult;
import ai.djl.training.dataset.Dataset;
import ai.djl.training.dataset.RandomAccessDataset;
import ai.djl.training.evaluator.Accuracy;
import ai.djl.training.listener.TrainingListener;
import ai.djl.training.loss.Loss;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.Pipeline;
import ai.djl.translate.TranslateException;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.apache.commons.cli.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图像分类（ResNet for Cifar10）模型训练实例.
 *
 * <p>详情 如下 <a
 * href="https://github.com/awslabs/djl/blob/master/examples/docs/train_cifar10_resnet.md">文档</a>
 * 有关运行此示例的信息.
 */
public final class TrainResnetWithCifar10 {

    private static final Logger logger = LoggerFactory.getLogger(TrainResnetWithCifar10.class);

    private TrainResnetWithCifar10() {}

    public static void main(String[] args)
            throws ParseException, ModelException, IOException, TranslateException {
        TrainResnetWithCifar10.runExample(args);
    }

    public static TrainingResult runExample(String[] args)
            throws IOException, ParseException, ModelException, TranslateException {
        Arguments arguments = Arguments.parseArgs(args);

        try (Model model = getModel(arguments)) {
            // 获取训练数据集
            RandomAccessDataset trainDataset = getDataset(Dataset.Usage.TRAIN, arguments);
            RandomAccessDataset validationDataset = getDataset(Dataset.Usage.TEST, arguments);

            // 设置培训配置
            DefaultTrainingConfig config = setupTrainingConfig(arguments);

            try (Trainer trainer = model.newTrainer(config)) {
                trainer.setMetrics(new Metrics());

                /*
                 * CIFAR10是32x32图像，并预处理为NCHW nArray.
                 * 轴1可用于第一批初始化.
                 */
                Shape inputShape = new Shape(1, 3, 32, 32);

                // 使用正确的输入形状初始化培训器
                trainer.initialize(inputShape);
                EasyTrain.fit(trainer, arguments.getEpoch(), trainDataset, validationDataset);

                TrainingResult result = trainer.getTrainingResult();
                model.setProperty("Epoch", String.valueOf(result.getEpoch()));
                model.setProperty(
                        "Accuracy",
                        String.format("%.5f", result.getValidateEvaluation("Accuracy")));
                model.setProperty("Loss", String.format("%.5f", result.getValidateLoss()));

                Path modelPath = Paths.get("build/model");
                model.save(modelPath, "resnetv1");

                Classifications classifications = testSaveParameters(model.getBlock(), modelPath);
                logger.info("预测结果: {}", classifications.topK(3));
                return result;
            }
        }
    }

    private static Model getModel(Arguments arguments)
            throws IOException, ModelNotFoundException, MalformedModelException {
        boolean isSymbolic = arguments.isSymbolic();
        boolean preTrained = arguments.isPreTrained();
        Map<String, String> options = arguments.getCriteria();
        Criteria.Builder<Image, Classifications> builder =
                Criteria.builder()
                        .optApplication(Application.CV.IMAGE_CLASSIFICATION)
                        .setTypes(Image.class, Classifications.class)
                        .optProgress(new ProgressBar())
                        .optArtifactId("resnet");
        if (isSymbolic) {
            // load the model
            builder.optGroupId("ai.djl.mxnet");
            if (options == null) {
                builder.optFilter("layers", "50");
                builder.optFilter("flavor", "v1");
            } else {
                builder.optFilters(options);
            }
            Model model = ModelZoo.loadModel(builder.build());
            SequentialBlock newBlock = new SequentialBlock();
            SymbolBlock block = (SymbolBlock) model.getBlock();
            block.removeLastBlock();
            newBlock.add(block);
            // 原始模型不包括展平
            // 所以在这里应用压平
            newBlock.add(Blocks.batchFlattenBlock());
            model.setBlock(newBlock);
            if (!preTrained) {
                model.getBlock().clear();
            }
            return model;
        }
        // 命令 resnet50
        if (preTrained) {
            builder.optGroupId(BasicModelZoo.GROUP_ID);
            if (options == null) {
                builder.optFilter("layers", "50");
                builder.optFilter("flavor", "v1");
                builder.optFilter("dataset", "cifar10");
            } else {
                builder.optFilters(options);
            }
            // 从DJL model zoo加载预先培训的命令ResNet50
            return ModelZoo.loadModel(builder.build());
        } else {
            // 构建新的ResNet50，无需预先训练权重
            Model model = Model.newInstance("resnetv1");
            Block resNet50 =
                    ResNetV1.builder()
                            .setImageShape(new Shape(3, 32, 32))
                            .setNumLayers(50)
                            .setOutSize(10)
                            .build();
            model.setBlock(resNet50);
            return model;
        }
    }

    private static Classifications testSaveParameters(Block block, Path path)
            throws IOException, ModelException, TranslateException {
        URL synsetUrl =
                new URL(
                        "https://mlrepo.djl.ai/model/cv/image_classification/ai/djl/mxnet/synset_cifar10.txt");
        ImageClassificationTranslator translator =
                ImageClassificationTranslator.builder()
                        .addTransform(new ToTensor())
                        .addTransform(new Normalize(Cifar10.NORMALIZE_MEAN, Cifar10.NORMALIZE_STD))
                        .optSynsetUrl(String.valueOf(synsetUrl))
                        .optApplySoftmax(true)
                        .build();

        Image img = ImageFactory.getInstance().fromUrl("src/test/resources/airplane1.png");

        Criteria<Image, Classifications> criteria =
                Criteria.builder()
                        .setTypes(Image.class, Classifications.class)
                        .optModelUrls(path.toUri().toString())
                        .optTranslator(translator)
                        .optBlock(block)
                        .optModelName("resnetv1")
                        .build();

        try (ZooModel<Image, Classifications> model = ModelZoo.loadModel(criteria);
                Predictor<Image, Classifications> predictor = model.newPredictor()) {
            return predictor.predict(img);
        }
    }

    private static DefaultTrainingConfig setupTrainingConfig(Arguments arguments) {
        return new DefaultTrainingConfig(Loss.softmaxCrossEntropyLoss())
                .addEvaluator(new Accuracy())
                .optDevices(Device.getDevices(arguments.getMaxGpus()))
                .addTrainingListeners(TrainingListener.Defaults.logging(arguments.getOutputDir()));
    }

    private static RandomAccessDataset getDataset(Dataset.Usage usage, Arguments arguments)
            throws IOException {
        Pipeline pipeline =
                new Pipeline(
                        new ToTensor(),
                        new Normalize(Cifar10.NORMALIZE_MEAN, Cifar10.NORMALIZE_STD));
        Cifar10 cifar10 =
                Cifar10.builder()
                        .optUsage(usage)
                        .setSampling(arguments.getBatchSize(), true)
                        .optLimit(arguments.getLimit())
                        .optPipeline(pipeline)
                        .build();
        cifar10.prepare(new ProgressBar());
        return cifar10;
    }
}