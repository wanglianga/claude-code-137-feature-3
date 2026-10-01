package com.community.medrecycle.domain.enums;

/** 药品分类：普通片剂 / 抗生素 / 精神类 / 注射器 / 外用药 / 液体药 */
public enum DrugCategory {
    TABLET("普通片剂/胶囊"),
    ANTIBIOTIC("抗生素类"),
    PSYCHOTROPIC("精神类药品"),
    SYRINGE("注射器/针剂"),
    EXTERNAL("外用药"),
    LIQUID("液体药");

    private final String description;

    DrugCategory(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
