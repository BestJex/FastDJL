/*
 * Copyright 2021 Apache All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package com.service;

/**
 * ClassName: Classification
 * Description: 分类接口
 * Author: James Zow
 * Date: 2020/11/6 9:06
 * Version:
 **/
public interface Classification {

    /**
     * 图像分类 interface
     * @param imagePath 图片地址
     * @return
     */
    public String ImageClassification(String imagePath);

}
