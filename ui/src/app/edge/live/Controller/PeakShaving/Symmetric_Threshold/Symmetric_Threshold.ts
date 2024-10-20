// @ts-strict-ignore
import { Component } from "@angular/core";
import { AbstractFlatWidget } from "src/app/shared/components/flat/abstract-flat-widget";

import { ChannelAddress, CurrentData, Utils } from "../../../../../shared/shared";
import { Controller_Symmetric_Threshold_PeakShavingModalComponent } from "./modal/modal.component";

@Component({
    selector: "Controller_Symmetric_Threshold_PeakShaving",
    templateUrl: "./Symmetric_Threshold.html",
})
export class Controller_Symmetric_Threshold_PeakShavingComponent extends AbstractFlatWidget {

    public activePower: number;
    public peakShavingPower: number;
    public rechargePower: number;
    public peakShavingThresholdPower: number;
    public peakShavedGridPower: number;
    public gridPowerWithoutPeakShaving: number;
    public peakShavingStateMachine: number;
    public readonly CONVERT_WATT_TO_KILOWATT = Utils.CONVERT_WATT_TO_KILOWATT;

    async presentModal() {
        const modal = await this.modalController.create({
            component: Controller_Symmetric_Threshold_PeakShavingModalComponent,
            componentProps: {
                component: this.component,
                edge: this.edge,
            },
        });
        modal.onDidDismiss().then(() => {
            this.service.getConfig().then(config => {
                this.component = config.components[this.componentId];
            });
        });
        return await modal.present();
    }

    protected override getChannelAddresses() {
        return [
            new ChannelAddress(this.component.properties["meter.id"], "ActivePower"),
            new ChannelAddress(this.componentId, "_PropertyPeakShavingPower"),
            new ChannelAddress(this.componentId, "_PropertyRechargePower"),
            new ChannelAddress(this.componentId, "_PropertyPeakShavingThresholdPower"),
            new ChannelAddress(this.componentId, "PeakShavedGridPower"),
            new ChannelAddress(this.componentId, "GridPowerWithoutPeakShaving"),
            new ChannelAddress(this.componentId, "PeakShavingStateMachine"),
        ];
    }
    protected override onCurrentData(currentData: CurrentData) {

        // activePower is 0 for negative Values
        this.activePower = currentData.allComponents[this.component.properties["meter.id"] + "/ActivePower"] >= 0
            ? currentData.allComponents[this.component.properties["meter.id"] + "/ActivePower"] : 0;
        this.peakShavingPower = this.component.properties["peakShavingPower"];
        this.rechargePower = this.component.properties["rechargePower"];
        this.peakShavingThresholdPower = this.component.properties["peakShavingThresholdPower"];
        this.peakShavedGridPower = currentData.allComponents[this.componentId + "/PeakShavedGridPower"] > 0 ? currentData.allComponents[this.componentId + "/PeakShavedGridPower"] : 0;
        this.gridPowerWithoutPeakShaving = currentData.allComponents[this.componentId + "/GridPowerWithoutPeakShaving"] >= 0 ? currentData.allComponents[this.componentId + "/GridPowerWithoutPeakShaving"] : 0;
        this.peakShavingStateMachine = currentData.allComponents[this.componentId + "/PeakShavingStateMachine"];
    }

}
